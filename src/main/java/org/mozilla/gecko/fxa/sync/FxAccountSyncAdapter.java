/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.fxa.sync;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mozilla.gecko.R;
import org.mozilla.gecko.background.common.log.Logger;
import org.mozilla.gecko.background.fxa.FxAccountClient;
import org.mozilla.gecko.background.fxa.FxAccountClient20;
import org.mozilla.gecko.background.fxa.SkewHandler;
import org.mozilla.gecko.browserid.BrowserIDKeyPair;
import org.mozilla.gecko.browserid.JSONWebTokenUtils;
import org.mozilla.gecko.browserid.RSACryptoImplementation;
import org.mozilla.gecko.browserid.verifier.BrowserIDRemoteVerifierClient;
import org.mozilla.gecko.browserid.verifier.BrowserIDVerifierDelegate;
import org.mozilla.gecko.fxa.FxAccountConstants;
import org.mozilla.gecko.fxa.activities.FxAccountStatusActivity;
import org.mozilla.gecko.fxa.authenticator.AndroidFxAccount;
import org.mozilla.gecko.fxa.authenticator.FxAccountAuthenticator;
import org.mozilla.gecko.fxa.login.FxAccountLoginStateMachine;
import org.mozilla.gecko.fxa.login.FxAccountLoginStateMachine.LoginStateMachineDelegate;
import org.mozilla.gecko.fxa.login.FxAccountLoginTransition.Transition;
import org.mozilla.gecko.fxa.login.Married;
import org.mozilla.gecko.fxa.login.State;
import org.mozilla.gecko.fxa.login.State.Action;
import org.mozilla.gecko.fxa.login.State.StateLabel;
import org.mozilla.gecko.sync.BackoffHandler;
import org.mozilla.gecko.sync.ExtendedJSONObject;
import org.mozilla.gecko.sync.GlobalSession;
import org.mozilla.gecko.sync.PrefsBackoffHandler;
import org.mozilla.gecko.sync.SharedPreferencesClientsDataDelegate;
import org.mozilla.gecko.sync.SyncConfiguration;
import org.mozilla.gecko.sync.Utils;
import org.mozilla.gecko.sync.crypto.KeyBundle;
import org.mozilla.gecko.sync.delegates.BaseGlobalSessionCallback;
import org.mozilla.gecko.sync.delegates.ClientsDataDelegate;
import org.mozilla.gecko.sync.net.AuthHeaderProvider;
import org.mozilla.gecko.sync.net.HawkAuthHeaderProvider;
import org.mozilla.gecko.sync.stage.GlobalSyncStage.Stage;
import org.mozilla.gecko.tokenserver.TokenServerClient;
import org.mozilla.gecko.tokenserver.TokenServerClientDelegate;
import org.mozilla.gecko.tokenserver.TokenServerException;
import org.mozilla.gecko.tokenserver.TokenServerToken;

import android.accounts.Account;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.Builder;

public class FxAccountSyncAdapter extends AbstractThreadedSyncAdapter {
  private static final String LOG_TAG = FxAccountSyncAdapter.class.getSimpleName();

  public static final int NOTIFICATION_ID = LOG_TAG.hashCode();

  // Tracks the last seen storage hostname for backoff purposes.
  private static final String PREF_BACKOFF_STORAGE_HOST = "backoffStorageHost";

  protected final ExecutorService executor;

  public FxAccountSyncAdapter(Context context, boolean autoInitialize) {
    super(context, autoInitialize);
    this.executor = Executors.newSingleThreadExecutor();
  }

  protected static class SyncDelegate {
    protected final Context context;
    protected final CountDownLatch latch;
    protected final SyncResult syncResult;
    protected final AndroidFxAccount fxAccount;

    public SyncDelegate(Context context, CountDownLatch latch, SyncResult syncResult, AndroidFxAccount fxAccount) {
      if (context == null) {
        throw new IllegalArgumentException("context must not be null");
      }
      if (latch == null) {
        throw new IllegalArgumentException("latch must not be null");
      }
      if (syncResult == null) {
        throw new IllegalArgumentException("syncResult must not be null");
      }
      if (fxAccount == null) {
        throw new IllegalArgumentException("fxAccount must not be null");
      }
      this.context = context;
      this.latch = latch;
      this.syncResult = syncResult;
      this.fxAccount = fxAccount;
    }

    /**
     * No error!  Say that we made progress.
     */
    protected void setSyncResultSuccess() {
      syncResult.stats.numUpdates += 1;
    }

    /**
     * Soft error. Say that we made progress, so that Android will sync us again
     * after exponential backoff.
     */
    protected void setSyncResultSoftError() {
      syncResult.stats.numUpdates += 1;
      syncResult.stats.numIoExceptions += 1;
    }

    /**
     * Hard error. We don't want Android to sync us again, even if we make
     * progress, until the user intervenes.
     */
    protected void setSyncResultHardError() {
      syncResult.stats.numAuthExceptions += 1;
    }

    public void handleSuccess() {
      Logger.info(LOG_TAG, "Sync succeeded.");
      setSyncResultSuccess();
      latch.countDown();
    }

    public void handleError(Exception e) {
      Logger.error(LOG_TAG, "Got exception syncing.", e);
      setSyncResultSoftError();
      // This is awful, but we need to propagate bad assertions back up the
      // chain somehow, and this will do for now.
      if (e instanceof TokenServerException) {
        // We should only get here *after* we're locked into the married state.
        State state = fxAccount.getState();
        if (state.getStateLabel() == StateLabel.Married) {
          Married married = (Married) state;
          fxAccount.setState(married.makeCohabitingState());
        }
      }
      latch.countDown();
    }

    /**
     * When the login machine terminates, we might not be in the
     * <code>Married</code> state, and therefore we can't sync. This method
     * messages as much to the user.
     * <p>
     * To avoid stopping us syncing altogether, we set a soft error rather than
     * a hard error. In future, we would like to set a hard error if we are in,
     * for example, the <code>Separated</code> state, and then have some user
     * initiated activity mark the Android account as ready to sync again. This
     * is tricky, though, so we play it safe for now.
     *
     * @param finalState
     *          that login machine ended in.
     */
    public void handleCannotSync(State finalState) {
      Logger.warn(LOG_TAG, "Cannot sync from state: " + finalState.getStateLabel());
      showNotification(context, finalState);
      setSyncResultSoftError();
      latch.countDown();
    }

    public void postponeSync(long millis) {
      if (millis <= 0) {
        Logger.debug(LOG_TAG, "Asked to postpone sync, but zero delay. Short-circuiting.");
      } else {
        // delayUntil is broken: https://code.google.com/p/android/issues/detail?id=65669
        // So we don't bother doing this. Instead, we rely on the periodic sync
        // we schedule, and the backoff handler for the rest.
        /*
        Logger.warn(LOG_TAG, "Postponing sync by " + millis + "ms.");
        syncResult.delayUntil = millis / 1000;
         */
      }
      setSyncResultSoftError();
      latch.countDown();
    }
  }

  protected static class SessionCallback implements BaseGlobalSessionCallback {
    protected final SyncDelegate syncDelegate;
    protected final SchedulePolicy schedulePolicy;
    protected volatile BackoffHandler storageBackoffHandler;

    public SessionCallback(SyncDelegate syncDelegate, SchedulePolicy schedulePolicy) {
      this.syncDelegate = syncDelegate;
      this.schedulePolicy = schedulePolicy;
    }

    public void setBackoffHandler(BackoffHandler backoffHandler) {
      this.storageBackoffHandler = backoffHandler;
    }

    @Override
    public boolean shouldBackOffStorage() {
      return storageBackoffHandler.delayMilliseconds() > 0;
    }

    @Override
    public void requestBackoff(long backoffMillis) {
      schedulePolicy.configureBackoffMillisOnBackoff(storageBackoffHandler, backoffMillis);
    }

    @Override
    public void informUpgradeRequiredResponse(GlobalSession session) {
      schedulePolicy.onUpgradeRequired();
    }

    @Override
    public void informUnauthorizedResponse(GlobalSession globalSession, URI oldClusterURL) {
      schedulePolicy.onUnauthorized();
    }

    @Override
    public void handleStageCompleted(Stage currentState, GlobalSession globalSession) {
    }

    @Override
    public void handleSuccess(GlobalSession globalSession) {
      Logger.info(LOG_TAG, "Global session succeeded.");

      // Get the number of clients, so we can schedule the sync interval accordingly.
      try {
        int clientsCount = globalSession.getClientsDelegate().getClientsCount();
        this.schedulePolicy.onSuccessfulSync(clientsCount);
      } finally {
        // Continue with the usual success flow.
        syncDelegate.handleSuccess();
      }
    }

    @Override
    public void handleError(GlobalSession globalSession, Exception e) {
      Logger.warn(LOG_TAG, "Global session failed."); // Exception will be dumped by delegate below.
      syncDelegate.handleError(e);
      // TODO: should we reduce the periodic sync interval?
    }

    @Override
    public void handleAborted(GlobalSession globalSession, String reason) {
      Logger.warn(LOG_TAG, "Global session aborted: " + reason);
      syncDelegate.handleError(null);
      // TODO: should we reduce the periodic sync interval?
    }
  };

  protected void syncWithAssertion(final String audience,
                                   final String assertion,
                                   final URI tokenServerEndpointURI,
                                   final BackoffHandler tokenBackoffHandler,
                                   final SharedPreferences sharedPrefs,
                                   final KeyBundle syncKeyBundle,
                                   final String clientState,
                                   final SessionCallback callback,
                                   final Bundle extras) {
    final TokenServerClientDelegate delegate = new TokenServerClientDelegate() {
      @Override
      public void handleSuccess(final TokenServerToken token) {
        FxAccountConstants.pii(LOG_TAG, "Got token! uid is " + token.uid + " and endpoint is " + token.endpoint + ".");

        final URI storageServerURI;
        try {
          storageServerURI = new URI(token.endpoint);
        } catch (URISyntaxException e) {
          handleError(e);
          return;
        }
        final String storageHostname = storageServerURI.getHost();

        // We back off on a per-host basis. When we have an endpoint URI from a token, we
        // can check on the backoff status for that host.
        // If we're supposed to be backing off, we abort the not-yet-started session.
        final BackoffHandler storageBackoffHandler = new PrefsBackoffHandler(sharedPrefs, ".storage");
        callback.setBackoffHandler(storageBackoffHandler);

        String lastStorageHost = sharedPrefs.getString(PREF_BACKOFF_STORAGE_HOST, null);
        if (lastStorageHost != null &&
            lastStorageHost.equalsIgnoreCase(storageHostname) &&
            !storageBackoffHandler.shouldSync(extras)) {
          Logger.info(LOG_TAG, "Not syncing: storage server requested backoff.");
          callback.handleAborted(null, "Storage backoff");
          return;
        }

        // Invalidate the previous backoff, because our storage host has changed,
        // or we never had one at all, or we're OK to sync.
        storageBackoffHandler.setEarliestNextRequest(0L);

        FxAccountGlobalSession globalSession = null;
        try {
          ClientsDataDelegate clientsDataDelegate = new SharedPreferencesClientsDataDelegate(sharedPrefs);

          // We compute skew over time using SkewHandler. This yields an unchanging
          // skew adjustment that the HawkAuthHeaderProvider uses to adjust its
          // timestamps. Eventually we might want this to adapt within the scope of a
          // global session.
          final SkewHandler tokenServerSkewHandler = SkewHandler.getSkewHandlerForHostname(storageHostname);
          final long tokenServerSkew = tokenServerSkewHandler.getSkewInSeconds();
          final AuthHeaderProvider authHeaderProvider = new HawkAuthHeaderProvider(token.id, token.key.getBytes("UTF-8"), false, tokenServerSkew);

          final Context context = getContext();
          final SyncConfiguration syncConfig = new SyncConfiguration(token.uid, authHeaderProvider, sharedPrefs, syncKeyBundle);

          globalSession = new FxAccountGlobalSession(token.endpoint, syncConfig, callback, context, extras, clientsDataDelegate);
          globalSession.start();
        } catch (Exception e) {
          callback.handleError(globalSession, e);
          return;
        }
      }

      @Override
      public void handleFailure(TokenServerException e) {
        debugAssertion(audience, assertion);
        handleError(e);
      }

      @Override
      public void handleError(Exception e) {
        Logger.error(LOG_TAG, "Failed to get token.", e);
        callback.handleError(null, e);
      }

      @Override
      public void handleBackoff(int backoffSeconds) {
        // This is the token server telling us to back off.
        tokenBackoffHandler.extendEarliestNextRequest(backoffSeconds * 1000);
      }
    };

    TokenServerClient tokenServerclient = new TokenServerClient(tokenServerEndpointURI, executor);
    tokenServerclient.getTokenFromBrowserIDAssertion(assertion, true, clientState, delegate);
  }

  protected static void showNotification(Context context, State state) {
    final NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

    final Action action = state.getNeededAction();
    if (action == Action.None) {
      Logger.info(LOG_TAG, "State " + state.getStateLabel() + " needs no action; cancelling any existing notification.");
      notificationManager.cancel(NOTIFICATION_ID);
      return;
    }

    final String title = context.getResources().getString(R.string.fxaccount_sync_sign_in_error_notification_title);
    final String text = context.getResources().getString(R.string.fxaccount_sync_sign_in_error_notification_text, state.email);
    Logger.info(LOG_TAG, "State " + state.getStateLabel() + " needs action; offering notification with title: " + title);
    FxAccountConstants.pii(LOG_TAG, "And text: " + text);

    final Intent notificationIntent = new Intent(context, FxAccountStatusActivity.class);
    final PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, 0);

    final Builder builder = new NotificationCompat.Builder(context);
    builder
      .setContentTitle(title)
      .setContentText(text)
      .setSmallIcon(R.drawable.ic_status_logo)
      .setAutoCancel(true)
      .setContentIntent(pendingIntent);
    notificationManager.notify(NOTIFICATION_ID, builder.build());
  }

  /**
   * A trivial Sync implementation that does not cache client keys,
   * certificates, or tokens.
   *
   * This should be replaced with a full {@link FxAccountAuthenticator}-based
   * token implementation.
   */
  @Override
  public void onPerformSync(final Account account, final Bundle extras, final String authority, ContentProviderClient provider, final SyncResult syncResult) {
    Logger.setThreadLogTag(FxAccountConstants.GLOBAL_LOG_TAG);
    Logger.resetLogging();

    Logger.info(LOG_TAG, "Syncing FxAccount" +
        " account named like " + Utils.obfuscateEmail(account.name) +
        " for authority " + authority +
        " with instance " + this + ".");

    final Context context = getContext();
    final AndroidFxAccount fxAccount = new AndroidFxAccount(context, account);
    if (FxAccountConstants.LOG_PERSONAL_INFORMATION) {
      fxAccount.dump();
    }

    final CountDownLatch latch = new CountDownLatch(1);
    final SyncDelegate syncDelegate = new SyncDelegate(getContext(), latch, syncResult, fxAccount);
    final SchedulePolicy schedulePolicy = new FxAccountSchedulePolicy(getContext(), fxAccount);

    try {
      final State state;
      try {
        state = fxAccount.getState();
      } catch (Exception e) {
        syncDelegate.handleError(e);
        return;
      }

      // This will be the same chunk of SharedPreferences that we pass through to GlobalSession/SyncConfiguration.
      final SharedPreferences sharedPrefs = fxAccount.getSyncPrefs();

      // Check for a backoff right here.
      final BackoffHandler schedulerBackoffHandler = new PrefsBackoffHandler(sharedPrefs, ".scheduler");
      if (!schedulerBackoffHandler.shouldSync(extras)) {
        Logger.info(LOG_TAG, "Not syncing (scheduler).");
        syncDelegate.postponeSync(schedulerBackoffHandler.delayMilliseconds());
        return;
      }

      // Set a small scheduled 'backoff' to rate-limit the next sync.
      schedulePolicy.configureBackoffMillisBeforeSyncing(schedulerBackoffHandler);

      final String audience = fxAccount.getAudience();
      final String authServerEndpoint = fxAccount.getAccountServerURI();
      final String tokenServerEndpoint = fxAccount.getTokenServerURI();
      final URI tokenServerEndpointURI = new URI(tokenServerEndpoint);

      // TODO: why doesn't the loginPolicy extract the audience from the account?
      final FxAccountClient client = new FxAccountClient20(authServerEndpoint, executor);
      final FxAccountLoginStateMachine stateMachine = new FxAccountLoginStateMachine();
      stateMachine.advance(state, StateLabel.Married, new LoginStateMachineDelegate() {
        @Override
        public FxAccountClient getClient() {
          return client;
        }

        @Override
        public long getCertificateDurationInMilliseconds() {
          return 12 * 60 * 60 * 1000;
        }

        @Override
        public long getAssertionDurationInMilliseconds() {
          return 15 * 60 * 1000;
        }

        @Override
        public BrowserIDKeyPair generateKeyPair() throws NoSuchAlgorithmException {
          return RSACryptoImplementation.generateKeyPair(1024);
        }

        @Override
        public void handleTransition(Transition transition, State state) {
          Logger.info(LOG_TAG, "handleTransition: " + transition + " to " + state.getStateLabel());
        }

        @Override
        public void handleFinal(State state) {
          Logger.info(LOG_TAG, "handleFinal: in " + state.getStateLabel());
          fxAccount.setState(state);
          schedulePolicy.onHandleFinal(state.getNeededAction());
          try {
            if (state.getStateLabel() != StateLabel.Married) {
              syncDelegate.handleCannotSync(state);
              return;
            }

            final Married married = (Married) state;
            SkewHandler skewHandler = SkewHandler.getSkewHandlerFromEndpointString(tokenServerEndpoint);
            final long now = System.currentTimeMillis();
            final long issuedAtMillis = now + skewHandler.getSkewInMillis();
            final long assertionDurationMillis = this.getAssertionDurationInMilliseconds();
            final String assertion = married.generateAssertion(audience, JSONWebTokenUtils.DEFAULT_ASSERTION_ISSUER, issuedAtMillis, assertionDurationMillis);

            /*
             * At this point we're in the correct state to sync, and we're ready to fetch
             * a token and do some work.
             *
             * But first we need to do two things:
             * 1. Check to see whether we're in a backoff situation for the token server.
             *    If we are, but we're not forcing a sync, then we go no further.
             * 2. Clear an existing backoff (if we're syncing it doesn't matter, and if
             *    we're forcing we'll get a new backoff if things are still bad).
             *
             * Note that we don't check the storage backoff before the token dance: the token
             * server tells us which server we're syncing to!
             *
             * That logic lives in the TokenServerClientDelegate elsewhere in this file.
             */

            final BackoffHandler tokenBackoffHandler = new PrefsBackoffHandler(sharedPrefs, ".token");
            if (!tokenBackoffHandler.shouldSync(extras)) {
              Logger.info(LOG_TAG, "Not syncing (token server).");
              syncDelegate.postponeSync(tokenBackoffHandler.delayMilliseconds());
              return;
            }

            final SessionCallback sessionCallback = new SessionCallback(syncDelegate, schedulePolicy);
            final KeyBundle syncKeyBundle = married.getSyncKeyBundle();
            final String clientState = married.getClientState();
            syncWithAssertion(audience, assertion, tokenServerEndpointURI, tokenBackoffHandler, sharedPrefs, syncKeyBundle, clientState, sessionCallback, extras);
          } catch (Exception e) {
            syncDelegate.handleError(e);
            return;
          }
        }
      });

      latch.await();
    } catch (Exception e) {
      Logger.error(LOG_TAG, "Got error syncing.", e);
      syncDelegate.handleError(e);
    }

    Logger.error(LOG_TAG, "Syncing done.");
  }

  protected void debugAssertion(String audience, String assertion) {
    final CountDownLatch verifierLatch = new CountDownLatch(1);
    BrowserIDRemoteVerifierClient client = new BrowserIDRemoteVerifierClient(URI.create(BrowserIDRemoteVerifierClient.DEFAULT_VERIFIER_URL));
    client.verify(audience, assertion, new BrowserIDVerifierDelegate() {
      @Override
      public void handleSuccess(ExtendedJSONObject response) {
        Logger.info(LOG_TAG, "Remote verifier returned success: " + response.toJSONString());
        verifierLatch.countDown();
      }

      @Override
      public void handleFailure(ExtendedJSONObject response) {
        Logger.warn(LOG_TAG, "Remote verifier returned failure: " + response.toJSONString());
        verifierLatch.countDown();
      }

      @Override
      public void handleError(Exception e) {
        Logger.error(LOG_TAG, "Remote verifier returned error.", e);
        verifierLatch.countDown();
      }
    });

    try {
      verifierLatch.await();
    } catch (InterruptedException e) {
      Logger.error(LOG_TAG, "Got error.", e);
    }
  }
}
