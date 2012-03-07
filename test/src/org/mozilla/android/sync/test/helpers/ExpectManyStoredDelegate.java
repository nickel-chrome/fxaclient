/* Any copyright is dedicated to the Public Domain.
   http://creativecommons.org/publicdomain/zero/1.0/ */

package org.mozilla.android.sync.test.helpers;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;

import junit.framework.AssertionFailedError;

import org.mozilla.gecko.sync.repositories.domain.Record;

public class ExpectManyStoredDelegate extends DefaultStoreDelegate {
  HashSet<String> expectedGUIDs;
  AtomicLong stored;

  public ExpectManyStoredDelegate(Record[] records) {
    HashSet<String> s = new HashSet<String>();
    for (Record record : records) {
      s.add(record.guid);
    }
    expectedGUIDs = s;
    stored = new AtomicLong(0);
  }

  @Override
  public void onStoreCompleted(long storeEnd) {
    try {
      assertEquals(stored.get(), expectedGUIDs.size());
      System.out.println("Notifying in onStoreCompleted.");
      performNotify();
    } catch (AssertionFailedError e) {
      performNotify(e);
    }
  }

  @Override
  public void onRecordStoreSucceeded(Record record) {
    try {
      assertTrue(expectedGUIDs.contains(record.guid));
    } catch (AssertionFailedError e) {
      performNotify(e);
    }
    stored.incrementAndGet();
  }
}
