/* Any copyright is dedicated to the Public Domain.
   http://creativecommons.org/publicdomain/zero/1.0/ */

package org.mozilla.android.sync.test.helpers;

import java.util.concurrent.ExecutorService;

import org.mozilla.gecko.sync.repositories.delegates.RepositorySessionStoreDelegate;
import org.mozilla.gecko.sync.repositories.domain.Record;

public class DefaultStoreDelegate extends DefaultDelegate implements RepositorySessionStoreDelegate {
  
  @Override
  public void notifyRecordStoreFailed(Exception ex, String guid) {
    performNotify("Store failed", ex);
  }

  @Override
  public void notifyRecordStoreSucceeded(String guid) {
    performNotify("DefaultStoreDelegate used", null);
  }

  @Override
  public void onStoreCompleted(long storeEnd) {
    performNotify("DefaultStoreDelegate used", null);
  }

  @Override
  public RepositorySessionStoreDelegate deferredStoreDelegate(final ExecutorService executor) {
    final RepositorySessionStoreDelegate self = this;
    return new RepositorySessionStoreDelegate() {

      @Override
      public void notifyRecordStoreSucceeded(final String guid) {
        executor.execute(new Runnable() {
          @Override
          public void run() {
            self.notifyRecordStoreSucceeded(guid);
          }
        });
      }

      @Override
      public void notifyRecordStoreFailed(final Exception ex, final String guid) {
        executor.execute(new Runnable() {
          @Override
          public void run() {
            self.notifyRecordStoreFailed(ex, guid);
          }
        });
      }

      @Override
      public void onStoreCompleted(final long storeEnd) {
        executor.execute(new Runnable() {
          @Override
          public void run() {
            self.onStoreCompleted(storeEnd);
          }
        });
      }

      @Override
      public RepositorySessionStoreDelegate deferredStoreDelegate(ExecutorService newExecutor) {
        if (newExecutor == executor) {
          return this;
        }
        throw new IllegalArgumentException("Can't re-defer this delegate.");
      }
    };
  }
}
