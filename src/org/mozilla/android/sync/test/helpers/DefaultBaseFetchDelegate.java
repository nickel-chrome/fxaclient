/* Any copyright is dedicated to the Public Domain.
   http://creativecommons.org/publicdomain/zero/1.0/ */

package org.mozilla.android.sync.test.helpers;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;

import java.util.Arrays;

import org.mozilla.android.sync.repositories.domain.Record;

import android.util.Log;

public class DefaultBaseFetchDelegate extends DefaultDelegate {
  public Record[] records = new Record[0];

  protected void onDone(Record[] records, String[] expected) {
    Log.i("rnewman", "onDone. Test Waiter is " + testWaiter());
    try {
      assertEquals(expected.length, records.length);
      for (Record record : records) {
        assertFalse(-1 == Arrays.binarySearch(expected, record.guid));
      }
      Log.i("rnewman", "Notifying success.");
      testWaiter().performNotify();
    } catch (AssertionError e) {
      Log.i("rnewman", "Notifying assertion failure.");
      testWaiter().performNotify(e);
    } catch (Exception e) {
      Log.i("rnewman", "Fucking no.");
      testWaiter().performNotify();
    }
  }
  
  public int recordCount() {
    return (this.records == null) ? 0 : this.records.length;
  }
}
