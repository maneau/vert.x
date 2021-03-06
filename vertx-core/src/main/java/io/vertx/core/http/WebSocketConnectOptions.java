/*
 * Copyright 2014 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.vertx.core.http;

import io.vertx.core.MultiMap;

import java.util.HashSet;
import java.util.Set;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class WebSocketConnectOptions extends RequestOptions {

  private int maxWebsocketFrameSize = 65536;
  private WebSocketVersion version = WebSocketVersion.RFC6455;
  private Set<String> subProtocols;

  public int getMaxWebsocketFrameSize() {
    return maxWebsocketFrameSize;
  }

  public WebSocketConnectOptions setMaxWebsocketFrameSize(int maxWebsocketFrameSize) {
    if (maxWebsocketFrameSize < 1) {
      throw new IllegalArgumentException("maxWebsocketFrameSize must be > 0");
    }
    this.maxWebsocketFrameSize = maxWebsocketFrameSize;
    return this;
  }

  public WebSocketVersion getVersion() {
    return version;
  }

  public WebSocketConnectOptions setVersion(WebSocketVersion version) {
    this.version = version;
    return this;
  }

  public WebSocketConnectOptions addSubProtocol(String subProtocol) {
    if (subProtocols == null) {
      subProtocols = new HashSet<>();
    }
    subProtocols.add(subProtocol);
    return this;
  }

  public Set<String> getSubProtocols() {
    return subProtocols;
  }

  @Override
  public WebSocketConnectOptions setPort(int port) {
    super.setPort(port);
    return this;
  }

  @Override
  public WebSocketConnectOptions setHost(String host) {
    super.setHost(host);
    return this;
  }

  @Override
  public WebSocketConnectOptions setHeaders(MultiMap headers) {
    super.setHeaders(headers);
    return this;
  }

  @Override
  public WebSocketConnectOptions setRequestURI(String requestURI) {
    super.setRequestURI(requestURI);
    return this;
  }

}
