/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.sync.net;

import java.io.IOException;
import java.security.GeneralSecurityException;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;

/**
 * ResourceDelegate implementers must ensure that HTTP responses
 * are fully consumed to ensure that connections are returned to
 * the pool:
 *
 *          EntityUtils.consume(entity);
 * @author rnewman
 *
 */
public interface ResourceDelegate {
  // Request augmentation.
  AuthHeaderProvider getAuthHeaderProvider();
  void addHeaders(HttpUriRequest request, HttpClient client);

  /**
   * The value of the User-Agent header to include with the request.
   *
   * @return User-Agent header value; null means do not set User-Agent header.
   */
  public String getUserAgent();

  // Response handling.

  /**
   * Override this to handle an HttpResponse.
   *
   * ResourceDelegate implementers <b>must</b> ensure that HTTP responses are
   * fully consumed to ensure that connections are returned to the pool, for
   * example by calling <code>EntityUtils.consume(response.getEntity())</code>.
   */
  void handleHttpResponse(HttpResponse response);
  void handleHttpProtocolException(ClientProtocolException e);
  void handleHttpIOException(IOException e);

  // During preparation.
  void handleTransportException(GeneralSecurityException e);

  // Connection parameters.
  int connectionTimeout();
  int socketTimeout();
}
