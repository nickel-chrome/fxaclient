/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.background.healthreport.prune;

import org.mozilla.gecko.background.common.log.Logger;
import org.mozilla.gecko.background.healthreport.EnvironmentBuilder;
import org.mozilla.gecko.background.healthreport.HealthReportConstants;
import org.mozilla.gecko.background.healthreport.HealthReportDatabaseStorage;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.ContentProviderClient;

/**
 * Manages scheduling of the pruning of old Firefox Health Report data.
 *
 * There are three main actions that take place:
 *   1) Excessive storage pruning: The recorded data is taking up an unreasonable amount of space.
 *   2) Expired data pruning: Data that is kept around longer than is useful.
 *   3) Periodic vacuuming: To deal with database bloat and fragmentation.
 *
 * (1) and (2) are performed periodically on their own schedules. (3) will occur via a timed
 * schedule (like (1) and (2)), or additionally when excessive database fragmentation occurs.
 *
 * Due to (3), auto_vacuum does not need to be enabled. However, by default it is enabled. Since
 * turning this feature off requires an expensive vacuum, we wait until the users' first vacuum
 * (which must be entirely time based - see {@link attemptVacuum}) to disable auto_vacuum.
 */
public class PrunePolicy {
  public static final String LOG_TAG = PrunePolicy.class.getSimpleName();

  protected final Context context;
  protected final SharedPreferences sharedPreferences;
  protected final Editor editor;
  protected final String profilePath;

  protected ContentProviderClient client;
  protected HealthReportDatabaseStorage storage;
  protected int environmentID;

  public PrunePolicy(final Context context, final SharedPreferences sharedPrefs, final String profilePath) {
    this.context = context;
    this.sharedPreferences = sharedPrefs;
    this.editor = new Editor(this.sharedPreferences.edit());
    this.profilePath = profilePath;

    this.environmentID = -1;
  }

  protected SharedPreferences getSharedPreferences() {
    return this.sharedPreferences;
  }

  public void tick(final long time) {
    try {
      attemptPruneBySize(time);
      attemptPruneByDuration(time);
      attemptVacuum(time);
    } catch (Exception e) {
      // While catching Exception is ordinarily bad form, this Service runs in the same process as
      // Fennec so if we crash, it crashes. Additionally, this Service runs regularly so these
      // crashes could be regular. Thus, we choose to quietly fail instead.
      Logger.warn(LOG_TAG, "Got exception pruning document.", e);
    } finally {
      editor.commit();
      releaseClient();
    }
  }

  protected boolean attemptPruneBySize(final long time) {
    final long nextPrune = getNextPruneBySizeTime();
    if (nextPrune < 0) {
      Logger.debug(LOG_TAG, "Initializing prune by size time.");
      editor.setNextPruneBySizeTime(time + getMinimumTimeBetweenPrunesBySize());
      return false;
    }

    // If the system clock is skewed into the past, making the time between prunes too long, reset
    // the clock.
    if (nextPrune > getPruneBySizeSkewLimitMillis() + time) {
      Logger.debug(LOG_TAG, "Clock skew detected - resetting prune by size time.");
      editor.setNextPruneBySizeTime(time + getMinimumTimeBetweenPrunesBySize());
      return false;
    }

    if (nextPrune > time) {
      Logger.debug(LOG_TAG, "Skipping prune by size - wait period has not yet elapsed.");
      return false;
    }

    // Prune environments first because their cascading deletions may delete some events.
    final HealthReportDatabaseStorage storage = getStorage();
    final int environmentCount = storage.getEnvironmentCount();
    if (environmentCount > getMaxEnvironmentCount()) {
      final int environmentPruneCount = environmentCount - getEnvironmentCountAfterPrune();
      Logger.debug(LOG_TAG, "Pruning " + environmentPruneCount + " environments.");
      storage.pruneEnvironments(environmentPruneCount);
    }

    final int eventCount = storage.getEventCount();
    if (eventCount > getMaxEventCount()) {
      final int eventPruneCount = eventCount - getEventCountAfterPrune();
      Logger.debug(LOG_TAG, "Pruning up to " + eventPruneCount + " events.");
      storage.pruneEvents(eventPruneCount);
    }
    editor.setNextPruneBySizeTime(time + getMinimumTimeBetweenPrunesBySize());
    return true;
  }

  protected boolean attemptPruneByDuration(final long time) {
    final long nextPrune = getNextPruneByDurationTime();
    if (nextPrune < 0) {
      Logger.debug(LOG_TAG, "Initializing prune by duration time.");
      editor.setNextPruneByDurationTime(time + getMinimumTimeBetweenPrunesByDuration());
      return false;
    }

    // If the system clock is skewed into the past, making the time between prunes too long, reset
    // the clock.
    if (nextPrune > getPruneByDurationSkewLimitMillis() + time) {
      Logger.debug(LOG_TAG, "Clock skew detected - resetting prune by duration time.");
      editor.setNextPruneByDurationTime(time + getMinimumTimeBetweenPrunesByDuration());
      return false;
    }

    if (nextPrune > time) {
      Logger.debug(LOG_TAG, "Skipping prune by duration - wait period has not yet elapsed.");
      return false;
    }

    final long oldEventTime = time - getEventExistenceDuration();
    Logger.debug(LOG_TAG, "Pruning data older than " + oldEventTime + ".");
    getStorage().deleteDataBefore(oldEventTime, getEnvironmentID());
    editor.setNextPruneByDurationTime(time + getMinimumTimeBetweenPrunesByDuration());
    return true;
  }

  /**
   * Attempt to vacuum the database. Vacuums will occur when there is excessive fragmentation or
   * the maximum duration between vacuums is exceeded.
   */
  protected boolean attemptVacuum(final long time) {
    // If auto_vacuum is enabled, there are no free pages and we can't get the free page ratio in
    // order to vacuum on fragmentation amount.
    final HealthReportDatabaseStorage storage = getStorage();
    if (storage.isAutoVacuumingDisabled()) {
      final float freePageRatio = storage.getFreePageRatio();
      final float freePageRatioLimit = getFreePageRatioLimit();
      if (freePageRatio > freePageRatioLimit) {
        Logger.debug(LOG_TAG, "Vacuuming based on fragmentation amount: " + freePageRatio + " / " +
            freePageRatioLimit);
        editor.setNextVacuumTime(time + getMinimumTimeBetweenVacuums());
        vacuumAndDisableAutoVacuuming(storage);
        return true;
      }
    }

    // Vacuum if max duration since last vacuum is exceeded.
    final long nextVacuum = getNextVacuumTime();
    if (nextVacuum < 0) {
      Logger.debug(LOG_TAG, "Initializing vacuum time.");
      editor.setNextVacuumTime(time + getMinimumTimeBetweenVacuums());
      return false;
    }

    // If the system clock is skewed into the past, making the time between prunes too long, reset
    // the clock.
    if (nextVacuum > getVacuumSkewLimitMillis() + time) {
      Logger.debug(LOG_TAG, "Clock skew detected - resetting vacuum time.");
      editor.setNextVacuumTime(time + getMinimumTimeBetweenVacuums());
      return false;
    }

    if (nextVacuum > time) {
      Logger.debug(LOG_TAG, "Skipping vacuum - wait period has not yet elapsed.");
      return false;
    }

    editor.setNextVacuumTime(time + getMinimumTimeBetweenVacuums());
    vacuumAndDisableAutoVacuuming(storage);
    return true;
  }

  protected void vacuumAndDisableAutoVacuuming(final HealthReportDatabaseStorage storage) {
    // The change to auto_vacuum will only take affect after a vacuum.
    storage.disableAutoVacuuming();
    storage.vacuum();
  }

  protected int getEnvironmentID() {
    if (environmentID < 0) {
      environmentID = getStorage().getEnvironment().register();
    }
    return environmentID;
  }

  /**
   * Retrieves the {@link HealthReportDatabaseStorage} associated with the profile of the policy.
   * For efficiency, the underlying {@link ContentProviderClient} and
   * {@link HealthReportDatabaseStorage} are cached for later invocations. However, this means a
   * call to this method MUST be accompanied by a call to {@link releaseClient}. Throws
   * {@link IllegalStateException} if the storage instance could not be retrieved - note that the
   * {@link ContentProviderClient} instance will not be closed in this case and
   * {@link releaseClient} should still be called.
   */
  protected HealthReportDatabaseStorage getStorage() {
    if (storage != null) {
      return storage;
    }

    client = EnvironmentBuilder.getContentProviderClient(context);
    if (client == null) {
      // TODO: Record prune failures and submit as part of FHR upload.
      Logger.warn(LOG_TAG, "Unable to get ContentProviderClient - throwing.");
      throw new IllegalStateException("Unable to get ContentProviderClient.");
    }

    try {
      storage = EnvironmentBuilder.getStorage(client, profilePath);
      if (storage == null) {
        // TODO: Record prune failures and submit as part of FHR upload.
        Logger.warn(LOG_TAG,"Unable to get HealthReportDatabaseStorage for " + profilePath +
            " - throwing.");
        throw new IllegalStateException("Unable to get HealthReportDatabaseStorage for " +
            profilePath + " (== null).");
      }
    } catch (ClassCastException ex) {
      // TODO: Record prune failures and submit as part of FHR upload.
      Logger.warn(LOG_TAG,"Unable to get HealthReportDatabaseStorage for " + profilePath +
          profilePath + " (ClassCastException).");
      throw new IllegalStateException("Unable to get HealthReportDatabaseStorage for " +
          profilePath + ".", ex);
    }

    return storage;
  }

  /**
   * Closes the underlying {@link ContentProviderClient} instance owned by this policy. MUST be
   * called before this policy is garbage collected.
   */
  protected void releaseClient() {
    if (client != null) {
      client.release();
      client = null;
    }
  }

  protected static class Editor {
    protected final SharedPreferences.Editor editor;

    public Editor(final SharedPreferences.Editor editor) {
      this.editor = editor;
    }

    public void commit() {
      editor.commit();
    }

    public Editor setNextPruneByDurationTime(final long time) {
      editor.putLong(HealthReportConstants.PREF_PRUNE_BY_DURATION_TIME, time);
      return this;
    }

    public Editor setNextPruneBySizeTime(final long time) {
      editor.putLong(HealthReportConstants.PREF_PRUNE_BY_SIZE_TIME, time);
      return this;
    }

    public Editor setNextVacuumTime(final long time) {
      editor.putLong(HealthReportConstants.PREF_VACUUM_TIME, time);
      return this;
    }
  }

  private long getNextPruneByDurationTime() {
    return getSharedPreferences().getLong(HealthReportConstants.PREF_PRUNE_BY_DURATION_TIME, -1L);
  }

  private long getEventExistenceDuration() {
    return HealthReportConstants.EVENT_EXISTENCE_DURATION;
  }

  private long getMinimumTimeBetweenPrunesByDuration() {
    return HealthReportConstants.MIN_MILLIS_BETWEEN_PRUNES_BY_DURATION;
  }

  private long getPruneByDurationSkewLimitMillis() {
    return HealthReportConstants.PRUNE_BY_DURATION_SKEW_LIMIT_MILLIS;
  }

  private long getNextPruneBySizeTime() {
    return getSharedPreferences().getLong(HealthReportConstants.PREF_PRUNE_BY_SIZE_TIME, -1L);
  }

  private long getMinimumTimeBetweenPrunesBySize() {
    return HealthReportConstants.MIN_MILLIS_BETWEEN_PRUNES_BY_SIZE;
  }

  private long getPruneBySizeSkewLimitMillis() {
    return HealthReportConstants.PRUNE_BY_SIZE_SKEW_LIMIT_MILLIS;
  }

  private int getMaxEnvironmentCount() {
    return HealthReportConstants.MAX_ENVIRONMENT_COUNT;
  }

  private int getEnvironmentCountAfterPrune() {
    return HealthReportConstants.ENVIRONMENT_COUNT_AFTER_PRUNE;
  }

  private int getMaxEventCount() {
    return HealthReportConstants.MAX_EVENT_COUNT;
  }

  private int getEventCountAfterPrune() {
    return HealthReportConstants.EVENT_COUNT_AFTER_PRUNE;
  }

  private long getNextVacuumTime() {
    return getSharedPreferences().getLong(HealthReportConstants.PREF_VACUUM_TIME, -1L);
  }

  private long getMinimumTimeBetweenVacuums() {
    return HealthReportConstants.MIN_MILLIS_BETWEEN_VACUUMS;
  }

  private long getVacuumSkewLimitMillis() {
    return HealthReportConstants.VACUUM_SKEW_LIMIT_MILLIS;
  }

  private float getFreePageRatioLimit() {
    return HealthReportConstants.DB_FREE_PAGE_RATIO_LIMIT;
  }
}
