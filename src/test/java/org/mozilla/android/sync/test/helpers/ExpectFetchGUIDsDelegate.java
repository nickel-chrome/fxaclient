package org.mozilla.android.sync.test.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;

import org.mozilla.android.sync.repositories.RepoStatusCode;
import org.mozilla.android.sync.repositories.RepositorySessionDelegate;

public class ExpectFetchGUIDsDelegate extends DefaultRepositorySessionDelegate
    implements RepositorySessionDelegate {
  private String[] expected;

  public ExpectFetchGUIDsDelegate(String[] guids) {
    expected = guids;
    Arrays.sort(expected);
  }

  public void guidsSinceCallback(RepoStatusCode status, String[] guids) {
    assertEquals(status, RepoStatusCode.DONE);
    assertEquals(guids.length, this.expected.length);
    for (String string : guids) {
      assertFalse(-1 == Arrays.binarySearch(this.expected, string));
    }
    testWaiter().performNotify();
  }
}