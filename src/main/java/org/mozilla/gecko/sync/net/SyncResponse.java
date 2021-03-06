/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.sync.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Date;
import java.util.Scanner;

import org.json.simple.parser.ParseException;
import org.mozilla.gecko.background.common.log.Logger;
import org.mozilla.gecko.sync.ExtendedJSONObject;
import org.mozilla.gecko.sync.NonObjectJSONException;
import org.mozilla.gecko.sync.Utils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.utils.DateUtils;

public class SyncResponse {
  private static final String HEADER_RETRY_AFTER = "retry-after";
  private static final String LOG_TAG = "SyncResponse";

  protected HttpResponse response;

  public SyncResponse() {
    super();
  }

  public SyncResponse(HttpResponse res) {
    response = res;
  }

  public HttpResponse httpResponse() {
    return this.response;
  }

  public int getStatusCode() {
    return this.response.getStatusLine().getStatusCode();
  }

  public boolean wasSuccessful() {
    return this.getStatusCode() == 200;
  }

  /**
   * Fetch the content type of the HTTP response body.
   *
   * @return a <code>Header</code> instance, or <code>null</code> if there was
   *         no body or no valid Content-Type.
   */
  public Header getContentType() {
    HttpEntity entity = this.response.getEntity();
    if (entity == null) {
      return null;
    }
    return entity.getContentType();
  }

  private String body = null;
  public String body() throws IllegalStateException, IOException {
    if (body != null) {
      return body;
    }
    InputStreamReader is = new InputStreamReader(this.response.getEntity().getContent());
    // Oh, Java, you are so evil.
    body = new Scanner(is).useDelimiter("\\A").next();
    return body;
  }

  /**
   * Return the body as a <b>non-null</b> <code>ExtendedJSONObject</code>.
   *
   * @return A non-null <code>ExtendedJSONObject</code>.
   *
   * @throws IllegalStateException
   * @throws IOException
   * @throws ParseException
   * @throws NonObjectJSONException
   */
  public ExtendedJSONObject jsonObjectBody() throws IllegalStateException,
                                            IOException, ParseException,
                                            NonObjectJSONException {
    if (body != null) {
      // Do it from the cached String.
      return ExtendedJSONObject.parseJSONObject(body);
    }

    HttpEntity entity = this.response.getEntity();
    if (entity == null) {
      throw new IOException("no entity");
    }

    InputStream content = entity.getContent();
    try {
      Reader in = new BufferedReader(new InputStreamReader(content, "UTF-8"));
      return ExtendedJSONObject.parseJSONObject(in);
    } finally {
      content.close();
    }
  }

  private boolean hasHeader(String h) {
    return this.response.containsHeader(h);
  }

  private static boolean missingHeader(String value) {
    return value == null ||
           value.trim().length() == 0;
  }

  private int getIntegerHeader(String h) throws NumberFormatException {
    if (this.hasHeader(h)) {
      Header header = this.response.getFirstHeader(h);
      String value  = header.getValue();
      if (missingHeader(value)) {
        Logger.warn(LOG_TAG, h + " header present but empty.");
        return -1;
      }
      return Integer.parseInt(value, 10);
    }
    return -1;
  }

  /**
   * @return A number of seconds, or -1 if the 'Retry-After' header was not present.
   */
  public int retryAfterInSeconds() throws NumberFormatException {
    if (!this.hasHeader(HEADER_RETRY_AFTER)) {
      return -1;
    }

    Header header = this.response.getFirstHeader(HEADER_RETRY_AFTER);
    String retryAfter = header.getValue();
    if (missingHeader(retryAfter)) {
      Logger.warn(LOG_TAG, "Retry-After header present but empty.");
      return -1;
    }

    try {
      return Integer.parseInt(retryAfter, 10);
    } catch (NumberFormatException e) {
      // Fall through to try date format.
    }

    Date thenDate = DateUtils.parseDate(retryAfter);
    if (thenDate == null) {
      Logger.warn(LOG_TAG, "Retry-After header neither integer nor date: " + retryAfter);
      return -1;
    }
    final long then = thenDate.getTime();
    final long now  = System.currentTimeMillis();
    return (int)((then - now) / 1000);     // Convert milliseconds to seconds.
  }

  /**
   * @return A number of seconds, or -1 if the 'X-Backoff' header was not
   *         present.
   */
  public int backoffInSeconds() throws NumberFormatException {
    return this.getIntegerHeader("x-backoff");
  }

  /**
   * @return A number of seconds, or -1 if the 'X-Weave-Backoff' header was not
   *         present.
   */
  public int weaveBackoffInSeconds() throws NumberFormatException {
    return this.getIntegerHeader("x-weave-backoff");
  }

  /**
   * Extract a number of seconds, or -1 if none of the specified headers were present.
   *
   * @param includeRetryAfter
   *          if <code>true</code>, the Retry-After header is excluded. This is
   *          useful for processing non-error responses where a Retry-After
   *          header would be unexpected.
   * @return the maximum of the three possible backoff headers, in seconds.
   */
  public int totalBackoffInSeconds(boolean includeRetryAfter) {
    int retryAfterInSeconds = -1;
    if (includeRetryAfter) {
      try {
        retryAfterInSeconds = retryAfterInSeconds();
      } catch (NumberFormatException e) {
      }
    }

    int weaveBackoffInSeconds = -1;
    try {
      weaveBackoffInSeconds = weaveBackoffInSeconds();
    } catch (NumberFormatException e) {
    }

    int backoffInSeconds = -1;
    try {
      backoffInSeconds = backoffInSeconds();
    } catch (NumberFormatException e) {
    }

    int totalBackoff = Math.max(retryAfterInSeconds, Math.max(backoffInSeconds, weaveBackoffInSeconds));
    if (totalBackoff < 0) {
      return -1;
    } else {
      return totalBackoff;
    }
  }

  /**
   * @return A number of milliseconds, or -1 if neither the 'Retry-After',
   *         'X-Backoff', or 'X-Weave-Backoff' header were present.
   */
  public long totalBackoffInMilliseconds() {
    long totalBackoff = totalBackoffInSeconds(true);
    if (totalBackoff < 0) {
      return -1;
    } else {
      return 1000 * totalBackoff;
    }
  }

  /**
   * The timestamp returned from a Sync server is a decimal number of seconds,
   * e.g., 1323393518.04.
   *
   * We want milliseconds since epoch.
   *
   * @return milliseconds since the epoch, as a long, or -1 if the header
   *         was missing or invalid.
   */
  public long normalizedWeaveTimestamp() {
    String h = "x-weave-timestamp";
    if (!this.hasHeader(h)) {
      return -1;
    }

    return Utils.decimalSecondsToMilliseconds(this.response.getFirstHeader(h).getValue());
  }

  public int weaveRecords() throws NumberFormatException {
    return this.getIntegerHeader("x-weave-records");
  }

  public int weaveQuotaRemaining() throws NumberFormatException {
    return this.getIntegerHeader("x-weave-quota-remaining");
  }

  public String weaveAlert() {
    if (this.hasHeader("x-weave-alert")) {
      return this.response.getFirstHeader("x-weave-alert").getValue();
    }
    return null;
  }
}
