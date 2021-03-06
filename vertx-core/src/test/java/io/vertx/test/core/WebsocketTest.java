/*
 * Copyright 2014 Red Hat, Inc.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.test.core;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CaseInsensitiveMultiMap;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.WebSocketVersion;
import io.vertx.core.impl.ConcurrentHashSet;
import io.vertx.core.json.impl.Base64;
import io.vertx.core.net.NetSocket;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class WebsocketTest extends VertxTestBase {

  private HttpClient client;
  private HttpServer server;

  @Before
  public void before() {
    client = vertx.createHttpClient(new HttpClientOptions());
  }

  @After
  public void after() throws Exception {
    client.close();
    if (server != null) {
      CountDownLatch latch = new CountDownLatch(1);
      server.close(ar -> {
        assertTrue(ar.succeeded());
        latch.countDown();
      });
      awaitLatch(latch);
    }
  }

  @Test
  public void testRejectHybi00() throws Exception {
    testReject(WebSocketVersion.HYBI_00);
  }

  @Test
  public void testRejectHybi08() throws Exception {
    testReject(WebSocketVersion.HYBI_08);
  }

  @Test
  public void testWSBinaryHybi00() throws Exception {
    testWS(true, WebSocketVersion.HYBI_00);
  }

  @Test
  public void testWSStringHybi00() throws Exception {
    testWS(false, WebSocketVersion.HYBI_00);
  }

  @Test
  public void testWSBinaryHybi08() throws Exception {
    testWS(true, WebSocketVersion.HYBI_08);
  }

  @Test
  public void testWSStringHybi08() throws Exception {
    testWS(false, WebSocketVersion.HYBI_08);
  }

  @Test
  public void testWSBinaryHybi17() throws Exception {
    testWS(true, WebSocketVersion.RFC6455);
  }

  @Test
  public void testWSStringHybi17() throws Exception {
    testWS(false, WebSocketVersion.RFC6455);
  }

  @Test
  public void testWriteFromConnectHybi00() throws Exception {
    testWriteFromConnectHandler(WebSocketVersion.HYBI_00);
  }

  @Test
  public void testWriteFromConnectHybi08() throws Exception {
    testWriteFromConnectHandler(WebSocketVersion.HYBI_08);
  }

  @Test
  public void testWriteFromConnectHybi17() throws Exception {
    testWriteFromConnectHandler(WebSocketVersion.RFC6455);
  }

  @Test
  public void testContinuationWriteFromConnectHybi08() throws Exception {
    testContinuationWriteFromConnectHandler(WebSocketVersion.HYBI_08);
  }

  @Test
  public void testContinuationWriteFromConnectHybi17() throws Exception {
    testContinuationWriteFromConnectHandler(WebSocketVersion.RFC6455);
  }

  @Test
  public void testValidSubProtocolHybi00() throws Exception {
    testValidSubProtocol(WebSocketVersion.HYBI_00);
  }

  @Test
  public void testValidSubProtocolHybi08() throws Exception {
    testValidSubProtocol(WebSocketVersion.HYBI_08);
  }

  @Test
  public void testValidSubProtocolHybi17() throws Exception {
    testValidSubProtocol(WebSocketVersion.RFC6455);
  }

  @Test
  public void testInvalidSubProtocolHybi00() throws Exception {
    testInvalidSubProtocol(WebSocketVersion.HYBI_00);
  }

  @Test
  public void testInvalidSubProtocolHybi08() throws Exception {
    testInvalidSubProtocol(WebSocketVersion.HYBI_08);
  }

  @Test
  public void testInvalidSubProtocolHybi17() throws Exception {
    testInvalidSubProtocol(WebSocketVersion.RFC6455);
  }

  // TODO close and exception tests
  // TODO pause/resume/drain tests

  @Test
  // Client trusts all server certs
  public void testTLSClientTrustAll() throws Exception {
    testTLS(false, false, true, false, false, true, true);
  }

  @Test
  // Server specifies cert that the client trusts (not trust all)
  public void testTLSClientTrustServerCert() throws Exception {
    testTLS(false, true, true, false, false, false, true);
  }

  @Test
  // Server specifies cert that the client doesn't trust
  public void testTLSClientUntrustedServer() throws Exception {
    testTLS(false, false, true, false, false, false, false);
  }

  @Test
  //Client specifies cert even though it's not required
  public void testTLSClientCertNotRequired() throws Exception {
    testTLS(true, true, true, true, false, false, true);
  }

  @Test
  //Client specifies cert and it's not required
  public void testTLSClientCertRequired() throws Exception {
    testTLS(true, true, true, true, true, false, true);
  }

  @Test
  //Client doesn't specify cert but it's required
  public void testTLSClientCertRequiredNoClientCert() throws Exception {
    testTLS(false, true, true, true, true, false, false);
  }

  @Test
  //Client specifies cert but it's not trusted
  public void testTLSClientCertClientNotTrusted() throws Exception {
    testTLS(true, true, true, false, true, false, false);
  }

  @Test
  // Test with cipher suites
  public void testTLSCipherSuites() throws Exception {
    testTLS(false, false, true, false, false, true, true, ENABLED_CIPHER_SUITES);
  }

  private void testTLS(boolean clientCert, boolean clientTrust,
                       boolean serverCert, boolean serverTrust,
                       boolean requireClientAuth, boolean clientTrustAll,
                       boolean shouldPass,
                       String... enabledCipherSuites) throws Exception {
    HttpClientOptions options = new HttpClientOptions();
    options.setSsl(true);
    if (clientTrustAll) {
      options.setTrustAll(true);
    }
    if (clientTrust) {
      options.setTrustStorePath(findFileOnClasspath("tls/client-truststore.jks")).setTrustStorePassword("wibble");
    }
    if (clientCert) {
      options.setKeyStorePath(findFileOnClasspath("tls/client-keystore.jks")).setKeyStorePassword("wibble");
    }
    for (String suite: enabledCipherSuites) {
      options.addEnabledCipherSuite(suite);
    }
    client = vertx.createHttpClient(options);
    HttpServerOptions serverOptions = new HttpServerOptions();
    serverOptions.setSsl(true);
    if (serverTrust) {
      serverOptions.setTrustStorePath(findFileOnClasspath("tls/server-truststore.jks")).setTrustStorePassword("wibble");
    }
    if (serverCert) {
      serverOptions.setKeyStorePath(findFileOnClasspath("tls/server-keystore.jks")).setKeyStorePassword("wibble");
    }
    if (requireClientAuth) {
      serverOptions.setClientAuthRequired(true);
    }
    for (String suite: enabledCipherSuites) {
      serverOptions.addEnabledCipherSuite(suite);
    }
    server = vertx.createHttpServer(serverOptions.setPort(4043));
    server.websocketHandler(ws -> {
      ws.dataHandler(ws::writeBuffer);
    });
    server.listen(ar -> {
      assertTrue(ar.succeeded());

      client.exceptionHandler(t -> {
        if (shouldPass) {
          t.printStackTrace();
          fail("Should not throw exception");
        } else {
          testComplete();
        }
      });
      client.connectWebsocket(new WebSocketConnectOptions().setPort(4043), ws -> {
        int size = 100;
        Buffer received = new Buffer();
        ws.dataHandler(data -> {
          received.appendBuffer(data);
          if (received.length() == size) {
            ws.close();
            testComplete();
          }
        });
        Buffer buff = new Buffer(TestUtils.randomByteArray(size));
        ws.writeBinaryFrame(buff);
      });
    });
    await();
  }


  @Test
  // Let's manually handle the websocket handshake and write a frame to the client
  public void testHandleWSManually() throws Exception {
    String path = "/some/path";
    String message = "here is some text data";

    server = vertx.createHttpServer(new HttpServerOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT)).requestHandler(req -> {
      NetSocket sock = getUpgradedNetSocket(req, path);
      // Let's write a Text frame raw
      Buffer buff = new Buffer();
      buff.appendByte((byte)129); // Text frame
      buff.appendByte((byte)message.length());
      buff.appendString(message);
      sock.writeBuffer(buff);
    });
    server.listen(ar -> {
      assertTrue(ar.succeeded());
      client.connectWebsocket(new WebSocketConnectOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT).setRequestURI(path), ws -> {
        ws.dataHandler(buff -> {
          assertEquals(message, buff.toString("UTF-8"));
          testComplete();
        });
      });
      client.exceptionHandler(t-> fail(t.getMessage()));
    });
    await();
  }

  @Test
  public void testSharedServersRoundRobin() throws Exception {

    int numServers = 5;
    int numConnections = numServers * 100;

    List<HttpServer> servers = new ArrayList<>();
    Set<HttpServer> connectedServers = new ConcurrentHashSet<>();
    Map<HttpServer, Integer> connectCount = new ConcurrentHashMap<>();

    CountDownLatch latchListen = new CountDownLatch(numServers);
    CountDownLatch latchConns = new CountDownLatch(numConnections);
    for (int i = 0; i < numServers; i++) {
      HttpServer theServer = vertx.createHttpServer(new HttpServerOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT));
      servers.add(theServer);
      theServer.websocketHandler(ws -> {
        connectedServers.add(theServer);
        Integer cnt = connectCount.get(theServer);
        int icnt = cnt == null ? 0 : cnt;
        icnt++;
        connectCount.put(theServer, icnt);
        latchConns.countDown();
      }).listen(ar -> {
        if (ar.succeeded()) {
          latchListen.countDown();
        } else {
          fail("Failed to bind server");
        }
      });
    }
    assertTrue(latchListen.await(10, TimeUnit.SECONDS));

    // Create a bunch of connections
    CountDownLatch latchClient = new CountDownLatch(numConnections);
    for (int i = 0; i < numConnections; i++) {
      client.connectWebsocket(new WebSocketConnectOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT).setRequestURI("/someuri"), ws -> {
        ws.closeHandler(v -> latchClient.countDown());
        ws.close();
      });
    }

    assertTrue(latchClient.await(10, TimeUnit.SECONDS));
    assertTrue(latchConns.await(10, TimeUnit.SECONDS));

    assertEquals(numServers, connectedServers.size());
    for (HttpServer server: servers) {
      assertTrue(connectedServers.contains(server));
    }
    assertEquals(numServers, connectCount.size());
    for (int cnt: connectCount.values()) {
      assertEquals(numConnections / numServers, cnt);
    }

    CountDownLatch closeLatch = new CountDownLatch(numServers);

    for (HttpServer server: servers) {
      server.close(ar -> {
        assertTrue(ar.succeeded());
        closeLatch.countDown();
      });
    }

    assertTrue(closeLatch.await(10, TimeUnit.SECONDS));

    testComplete();
  }

  @Test
  public void testSharedServersRoundRobinWithOtherServerRunningOnDifferentPort() throws Exception {
    // Have a server running on a different port to make sure it doesn't interact
    CountDownLatch latch = new CountDownLatch(1);
    HttpServer theServer = vertx.createHttpServer(new HttpServerOptions().setPort(4321));
    theServer.websocketHandler(ws -> {
      fail("Should not connect");
    }).listen(ar -> {
      if (ar.succeeded()) {
        latch.countDown();
      } else {
        fail("Failed to bind server");
      }
    });
    awaitLatch(latch);
    testSharedServersRoundRobin();
  }

  @Test
  public void testSharedServersRoundRobinButFirstStartAndStopServer() throws Exception {
    // Start and stop a server on the same port/host before hand to make sure it doesn't interact
    CountDownLatch latch = new CountDownLatch(1);
    HttpServer theServer = vertx.createHttpServer(new HttpServerOptions().setPort(4321));
    theServer.websocketHandler(ws -> {
      fail("Should not connect");
    }).listen(ar -> {
      if (ar.succeeded()) {
        latch.countDown();
      } else {
        fail("Failed to bind server");
      }
    });
    awaitLatch(latch);
    CountDownLatch closeLatch = new CountDownLatch(1);
    theServer.close(ar -> {
      assertTrue(ar.succeeded());
      closeLatch.countDown();
    });
    assertTrue(closeLatch.await(10, TimeUnit.SECONDS));
    testSharedServersRoundRobin();
  }

  @Test
  public void testOptions() throws Exception {
    WebSocketConnectOptions options = new WebSocketConnectOptions();
    assertEquals(80, options.getPort());
    assertEquals(options, options.setPort(1234));
    assertEquals(1234, options.getPort());
    try {
      options.setPort(0);
      fail("Should throw exception");
    } catch (IllegalArgumentException e) {
      // OK
    }
    try {
      options.setPort(-1);
      fail("Should throw exception");
    } catch (IllegalArgumentException e) {
      // OK
    }
    try {
      options.setPort(65536);
      fail("Should throw exception");
    } catch (IllegalArgumentException e) {
      // OK
    }
    assertEquals("localhost", options.getHost());
    String randString = TestUtils.randomUnicodeString(100);
    assertEquals(options, options.setHost(randString));
    assertEquals(randString, options.getHost());
    MultiMap headers = new CaseInsensitiveMultiMap();
    assertNull(options.getHeaders());
    assertEquals(options, options.setHeaders(headers));
    assertSame(headers, options.getHeaders());
    randString = TestUtils.randomUnicodeString(100);
    assertEquals("/", options.getRequestURI());
    assertEquals(options, options.setRequestURI(randString));
    assertEquals(randString, options.getRequestURI());
    options.putHeader("foo", "bar");
    assertNotNull(options.getHeaders());
    assertEquals("bar", options.getHeaders().get("foo"));
    assertEquals(65536, options.getMaxWebsocketFrameSize());
    int rand = TestUtils.randomPositiveInt();
    assertEquals(options, options.setMaxWebsocketFrameSize(rand));
    assertEquals(rand, options.getMaxWebsocketFrameSize());
    try {
      options.setMaxWebsocketFrameSize(0);
      fail("Should throw exception");
    } catch (IllegalArgumentException e) {
      //OK
    }
    try {
      options.setMaxWebsocketFrameSize(-1);
      fail("Should throw exception");
    } catch (IllegalArgumentException e) {
      //OK
    }
    assertEquals(WebSocketVersion.RFC6455, options.getVersion());
    assertEquals(options, options.setVersion(WebSocketVersion.HYBI_00));
    assertEquals(WebSocketVersion.HYBI_00, options.getVersion());

    assertNull(options.getSubProtocols());
    assertEquals(options, options.addSubProtocol("foo"));
    assertEquals(options, options.addSubProtocol("bar"));
    assertNotNull(options.getSubProtocols());
    assertTrue(options.getSubProtocols().contains("foo"));
    assertTrue(options.getSubProtocols().contains("bar"));
  }

  private String sha1(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA1");
      //Hash the data
      byte[] bytes = md.digest(s.getBytes("UTF-8"));
      return Base64.encodeBytes(bytes);
    } catch (Exception e) {
      throw new InternalError("Failed to compute sha-1");
    }
  }


  private NetSocket getUpgradedNetSocket(HttpServerRequest req, String path) {
    assertEquals(path, req.path());
    assertEquals("Upgrade", req.headers().get("Connection"));
    NetSocket sock = req.netSocket();
    String secHeader = req.headers().get("Sec-WebSocket-Key");
    String tmp = secHeader + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    String encoded = sha1(tmp);
    sock.writeString("HTTP/1.1 101 Web Socket Protocol Handshake\r\n" +
      "Upgrade: WebSocket\r\n" +
      "Connection: Upgrade\r\n" +
      "Sec-WebSocket-Accept: " + encoded + "\r\n" +
      "\r\n");
    return sock;
  }

  private void testWS(final boolean binary, final WebSocketVersion version) throws Exception {

    String path = "/some/path";
    String query = "foo=bar&wibble=eek";
    String uri = path + "?" + query;

    server = vertx.createHttpServer(new HttpServerOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT)).websocketHandler(ws -> {
      assertEquals(uri, ws.uri());
      assertEquals(path, ws.path());
      assertEquals(query, ws.query());
      assertEquals("Upgrade", ws.headers().get("Connection"));
      ws.dataHandler(data -> ws.writeBuffer(data));
    });

    server.listen(ar -> {
      assertTrue(ar.succeeded());
      int bsize = 100;
      int sends = 10;

      client.connectWebsocket(new WebSocketConnectOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT).setRequestURI(path + "?" + query).setVersion(version), ws -> {
        final Buffer received = new Buffer();
        ws.dataHandler(data -> {
          received.appendBuffer(data);
          if (received.length() == bsize * sends) {
            ws.close();
            testComplete();
          }
        });
        final Buffer sent = new Buffer();
        for (int i = 0; i < sends; i++) {
          if (binary) {
            Buffer buff = new Buffer(TestUtils.randomByteArray(bsize));
            ws.writeBinaryFrame(buff);
            sent.appendBuffer(buff);
          } else {
            String str = TestUtils.randomAlphaString(bsize);
            ws.writeTextFrame(str);
            sent.appendBuffer(new Buffer(str, "UTF-8"));
          }
        }
      });
    });
    await();
  }

  private void testContinuationWriteFromConnectHandler(final WebSocketVersion version) throws Exception {
    String path = "/some/path";
    String firstFrame = "AAA";
    String continuationFrame = "BBB";

    server = vertx.createHttpServer(new HttpServerOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT)).requestHandler(req -> {
      NetSocket sock = getUpgradedNetSocket(req, path);

      // Let's write a Text frame raw
      Buffer buff = new Buffer();
      buff.appendByte((byte) 0x01); // Incomplete Text frame
      buff.appendByte((byte) firstFrame.length());
      buff.appendString(firstFrame);
      sock.writeBuffer(buff);

      buff = new Buffer();
      buff.appendByte((byte) (0x00 | 0x80)); // Complete continuation frame
      buff.appendByte((byte) continuationFrame.length());
      buff.appendString(continuationFrame);
      sock.writeBuffer(buff);
    });

    server.listen(ar -> {
      assertTrue(ar.succeeded());
      client.connectWebsocket(new WebSocketConnectOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT).setRequestURI(path).setVersion(version), ws -> {
        AtomicBoolean receivedFirstFrame = new AtomicBoolean();
        ws.frameHandler(received -> {
          Buffer receivedBuffer = new Buffer(received.textData());
          if (!received.isFinalFrame()) {
            assertEquals(firstFrame, receivedBuffer.toString());
            receivedFirstFrame.set(true);
          } else if (receivedFirstFrame.get() && received.isFinalFrame()) {
            assertEquals(continuationFrame, receivedBuffer.toString());
            ws.close();
            testComplete();
          }
        });
      });
    });
    await();
  }

  private void testWriteFromConnectHandler(final WebSocketVersion version) throws Exception {

    String path = "/some/path";
    Buffer buff = new Buffer("AAA");

    server = vertx.createHttpServer(new HttpServerOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT)).websocketHandler(ws -> {
      assertEquals(path, ws.path());
      ws.writeBinaryFrame(buff);
    });
    server.listen(ar -> {
      assertTrue(ar.succeeded());
      client.connectWebsocket(new WebSocketConnectOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT).setRequestURI(path).setVersion(version), ws -> {
        Buffer received = new Buffer();
        ws.dataHandler(data -> {
          received.appendBuffer(data);
          if (received.length() == buff.length()) {
            assertTrue(TestUtils.buffersEqual(buff, received));
            ws.close();
            testComplete();
          }
        });
      });
    });
    await();
  }

  private void testValidSubProtocol(final WebSocketVersion version) throws Exception {
    String path = "/some/path";
    String subProtocol = "myprotocol";
    Buffer buff = new Buffer("AAA");
    server = vertx.createHttpServer(new HttpServerOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT).addWebsocketSubProtocol(subProtocol)).websocketHandler(ws -> {
      assertEquals(path, ws.path());
      ws.writeBinaryFrame(buff);
    });
    server.listen(ar -> {
      assertTrue(ar.succeeded());
      client.connectWebsocket(new WebSocketConnectOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT).setRequestURI(path).setVersion(version).addSubProtocol(subProtocol), ws -> {
        final Buffer received = new Buffer();
        ws.dataHandler(data -> {
          received.appendBuffer(data);
          if (received.length() == buff.length()) {
            assertTrue(TestUtils.buffersEqual(buff, received));
            ws.close();
            testComplete();
          }
        });
      });
    });
    await();
  }

  private void testInvalidSubProtocol(final WebSocketVersion version) throws Exception {
    String path = "/some/path";
    String subProtocol = "myprotocol";
    Buffer buff = new Buffer("AAA");

    server = vertx.createHttpServer(new HttpServerOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT).addWebsocketSubProtocol("invalid")).websocketHandler(ws -> {
      assertEquals(path, ws.path());
      ws.writeBinaryFrame(buff);
    });
    server.listen(ar -> {
      assertTrue(ar.succeeded());
      client.connectWebsocket(new WebSocketConnectOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT).setRequestURI(path).setVersion(version).addSubProtocol(subProtocol), ws -> {
        final Buffer received = new Buffer();
        ws.dataHandler(data -> {
          received.appendBuffer(data);
          if (received.length() == buff.length()) {
            assertTrue(TestUtils.buffersEqual(buff, received));
            ws.close();
            testComplete();
          }
        });
      });
    });
    await();
  }

  private void testReject(final WebSocketVersion version) throws Exception {

    String path = "/some/path";

    server = vertx.createHttpServer(new HttpServerOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT)).websocketHandler(ws -> {
      assertEquals(path, ws.path());
      ws.reject();
    });

    server.listen(ar -> {
      assertTrue(ar.succeeded());
      client.exceptionHandler(t -> testComplete());
      client.connectWebsocket(new WebSocketConnectOptions().setPort(HttpTestBase.DEFAULT_HTTP_PORT).setRequestURI(path).setVersion(version), ws -> fail("Should not be called"));
    });
    await();
  }
}
