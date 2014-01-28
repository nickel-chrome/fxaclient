/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.fxa.activities;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.mozilla.gecko.R;
import org.mozilla.gecko.background.common.log.Logger;
import org.mozilla.gecko.background.fxa.FxAccountClient;
import org.mozilla.gecko.background.fxa.FxAccountClient10.RequestDelegate;
import org.mozilla.gecko.background.fxa.FxAccountClient20;
import org.mozilla.gecko.background.fxa.FxAccountClientException.FxAccountClientRemoteException;
import org.mozilla.gecko.fxa.FxAccountConstants;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Activity which displays account created successfully screen to the user, and
 * starts them on the email verification path.
 */
public class FxAccountConfirmAccountActivity extends FxAccountAbstractActivity implements OnClickListener {
  private static final String LOG_TAG = FxAccountConfirmAccountActivity.class.getSimpleName();

  protected byte[] sessionToken;
  protected TextView emailText;

  public FxAccountConfirmAccountActivity() {
    super(CANNOT_RESUME_WHEN_NO_ACCOUNTS_EXIST);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void onCreate(Bundle icicle) {
    Logger.debug(LOG_TAG, "onCreate(" + icicle + ")");

    super.onCreate(icicle);
    setContentView(R.layout.fxaccount_confirm_account);

    emailText = (TextView) ensureFindViewById(null, R.id.email, "email text");
    if (getIntent() != null && getIntent().getExtras() != null) {
      Bundle extras = getIntent().getExtras();
      emailText.setText(extras.getString("email"));
      sessionToken = extras.getByteArray("sessionToken");
    }

    View resendLink = ensureFindViewById(null, R.id.resend_confirmation_email_link, "resend confirmation email link");
    resendLink.setOnClickListener(this);

    if (sessionToken == null) {
      resendLink.setEnabled(false);
      resendLink.setClickable(false);
    }
  }

  public static class FxAccountResendCodeTask extends FxAccountSetupTask<Void> {
    protected static final String LOG_TAG = FxAccountResendCodeTask.class.getSimpleName();

    protected final byte[] sessionToken;

    public FxAccountResendCodeTask(Context context, byte[] sessionToken, FxAccountClient client, RequestDelegate<Void> delegate) {
      super(context, null, client, delegate);
      this.sessionToken = sessionToken;
    }

    @Override
    protected InnerRequestDelegate<Void> doInBackground(Void... arg0) {
      try {
        client.resendCode(sessionToken, innerDelegate);
        latch.await();
        return innerDelegate;
      } catch (Exception e) {
        Logger.error(LOG_TAG, "Got exception signing in.", e);
        delegate.handleError(e);
      }
      return null;
    }
  }

  protected class ResendCodeDelegate implements RequestDelegate<Void> {
    @Override
    public void handleError(Exception e) {
      Logger.warn(LOG_TAG, "Got exception requesting fresh confirmation link; ignoring.", e);
      Toast.makeText(getApplicationContext(), R.string.fxaccount_confirm_verification_link_not_sent, Toast.LENGTH_LONG).show();
    }

    @Override
    public void handleFailure(FxAccountClientRemoteException e) {
      handleError(e);
    }

    @Override
    public void handleSuccess(Void result) {
      Toast.makeText(getApplicationContext(), R.string.fxaccount_confirm_verification_link_sent, Toast.LENGTH_SHORT).show();
    }
  }

  protected void resendCode(byte[] sessionToken) {
    String serverURI = FxAccountConstants.DEFAULT_AUTH_SERVER_ENDPOINT;
    RequestDelegate<Void> delegate = new ResendCodeDelegate();
    Executor executor = Executors.newSingleThreadExecutor();
    FxAccountClient client = new FxAccountClient20(serverURI, executor);
    new FxAccountResendCodeTask(this, sessionToken, client, delegate).execute();
  }

  @Override
  public void onClick(View v) {
    resendCode(sessionToken);
  }
}
