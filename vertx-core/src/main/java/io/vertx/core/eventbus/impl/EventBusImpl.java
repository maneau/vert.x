/*
 * Copyright (c) 2011-2013 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.core.eventbus.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.eventbus.Registration;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import io.vertx.core.impl.Closeable;
import io.vertx.core.impl.ContextImpl;
import io.vertx.core.impl.FutureResultImpl;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.impl.LoggerFactory;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.impl.ServerID;
import io.vertx.core.spi.cluster.AsyncMultiMap;
import io.vertx.core.spi.cluster.ChoosableIterable;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.core.parsetools.RecordParser;

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class EventBusImpl implements EventBus {

  private static final Logger log = LoggerFactory.getLogger(EventBusImpl.class);

  private static final Buffer PONG = new Buffer(new byte[] { (byte)1 });
  private static final long PING_INTERVAL = 20000;
  private static final long PING_REPLY_INTERVAL = 20000;
  private final VertxInternal vertx;
  private ServerID serverID;
  private NetServer server;
  private AsyncMultiMap<String, ServerID> subs;
  private long defaultReplyTimeout = -1;
  private final ConcurrentMap<ServerID, ConnectionHolder> connections = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Handlers> handlerMap = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, MessageCodec<?>> codecMap = new ConcurrentHashMap<>();
  private final ClusterManager clusterMgr;
  private final AtomicLong replySequence = new AtomicLong(0);
  private boolean closed;

  public EventBusImpl(VertxInternal vertx) {
    // Just some dummy server ID
    this.vertx = vertx;
    this.serverID = new ServerID(-1, "localhost");
    this.server = null;
    this.subs = null;
    this.clusterMgr = null;
  }

  public EventBusImpl(VertxInternal vertx, int port, String hostname, ClusterManager clusterManager) {
    this(vertx, port, hostname, clusterManager, null);
  }

  public EventBusImpl(VertxInternal vertx, int port, String hostname, ClusterManager clusterManager,
                      Handler<AsyncResult<Void>> listenHandler) {
    this.vertx = vertx;
    this.clusterMgr = clusterManager;
    this.subs = clusterMgr.getAsyncMultiMap("subs");
    this.server = setServer(port, hostname, listenHandler);
  }

  @Override
  public EventBus send(String address, Object message) {
    return send(address, message, null);
  }

  @Override
  public <T> EventBus send(String address, Object message, Handler<Message<T>> replyHandler) {
    sendOrPub(createMessage(true, address, message), replyHandler);
    return this;
  }

  @Override
  public <T> EventBus sendWithTimeout(String address, Object message, long timeout, Handler<AsyncResult<Message<T>>> replyHandler) {
    sendOrPubWithTimeout(createMessage(true, address, message), replyHandler, timeout);
    return this;
  }

  @Override
  public EventBus publish(String address, Object message) {
    sendOrPub(createMessage(false, address, message), null);
    return this;
  }

  @Override
  public Registration registerHandler(String address, Handler<? extends Message> handler) {
    return registerHandler(address, handler, false, false, -1);
  }

  @Override
  public Registration registerLocalHandler(String address, Handler<? extends Message> handler) {
    return registerHandler(address, handler, false, true, -1);
  }

  @Override
  public <T> EventBus registerCodec(Class<T> type, MessageCodec<T> codec) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(codec);
    codecMap.put(type.getName(), codec);
    return this;
  }

  @Override
  public <T> EventBus unregisterCodec(Class<T> type) {
    Objects.requireNonNull(type);
    codecMap.remove(type.getName());
    return this;
  }

  @Override
  public void close(Handler<AsyncResult<Void>> doneHandler) {
		if (clusterMgr != null) {
			clusterMgr.leave();
		}
		if (server != null) {
			server.close(doneHandler);
		} else if (doneHandler != null) {
      vertx.runOnContext(v-> doneHandler.handle(new FutureResultImpl<>((Void)null)));
    }
  }

  @Override
  public EventBus setDefaultReplyTimeout(long timeoutMs) {
    this.defaultReplyTimeout = timeoutMs;
    return this;
  }

  @Override
  public long getDefaultReplyTimeout() {
    return defaultReplyTimeout;
  }

  <T, U> void sendReply(ServerID dest, BaseMessage<U> message, Handler<Message<T>> replyHandler) {
    sendOrPub(dest, message, replyHandler, -1);
  }

  <T, U> void sendReplyWithTimeout(ServerID dest, BaseMessage<U> message, long timeout, Handler<AsyncResult<Message<T>>> replyHandler) {
    if (message.address == null) {
      sendNoHandlersFailure(replyHandler);
    } else {
      Handler<Message<T>> handler = convertHandler(replyHandler);
      sendOrPub(dest, message, handler, replyHandler, timeout);
    }
  }

  <U> BaseMessage<U> createMessage(boolean send, String address, U message) {
    BaseMessage bm;
    if (message instanceof String) {
      bm = new StringMessage(send, address, (String)message);
    } else if (message instanceof Buffer) {
      bm = new BufferMessage(send, address, (Buffer)message);
    } else if (message instanceof JsonObject) {
      bm = new JsonObjectMessage(send, address, (JsonObject)message);
    } else if (message instanceof JsonArray) {
      bm = new JsonArrayMessage(send, address, (JsonArray)message);
    } else if (message instanceof byte[]) {
      bm = new ByteArrayMessage(send, address, (byte[])message);
    } else if (message instanceof Integer) {
      bm = new IntMessage(send, address, (Integer)message);
    } else if (message instanceof Long) {
      bm = new LongMessage(send, address, (Long)message);
    } else if (message instanceof Float) {
      bm = new FloatMessage(send, address, (Float)message);
    } else if (message instanceof Double) {
      bm = new DoubleMessage(send, address, (Double)message);
    } else if (message instanceof Boolean) {
      bm = new BooleanMessage(send, address, (Boolean)message);
    } else if (message instanceof Short) {
      bm = new ShortMessage(send, address, (Short)message);
    } else if (message instanceof Character) {
      bm = new CharacterMessage(send, address, (Character)message);
    } else if (message instanceof Byte) {
      bm = new ByteMessage(send, address, (Byte)message);
    } else if (message == null) {
      bm = new StringMessage(send, address, null);
    } else {
      String typeName = message.getClass().getName();
      MessageCodec<?>  codec;
      if (clusterMgr != null) {
        codec = codecMap.get(typeName);
        if (codec == null) {
          throw new IllegalArgumentException("No codec registered for " + message.getClass() + " on the event bus: " + message);
        }
      } else {
        codec = null;
      }
      @SuppressWarnings("unchecked")
      ObjectMessage<?> om = new ObjectMessage(send, address, message, typeName, codec);
      bm = om;
    }
    bm.bus = this;

    return bm;
  }

  private NetServer setServer(int port, String hostName, Handler<AsyncResult<Void>> listenHandler) {
    NetServer server = vertx.createNetServer(new NetServerOptions().setPort(port).setHost(hostName)).connectHandler(socket -> {
      RecordParser parser = RecordParser.newFixed(4, null);
      Handler<Buffer> handler = new Handler<Buffer>() {
        int size = -1;
        public void handle(Buffer buff) {
          if (size == -1) {
            size = buff.getInt(0);
            parser.fixedSizeMode(size);
          } else {
            BaseMessage received = MessageFactory.read(buff, codecMap);
            if (received.type() == MessageFactory.TYPE_PING) {
              // Send back a pong - a byte will do
              socket.writeBuffer(PONG);
            } else {
              receiveMessage(received, -1, null, null);
            }
            parser.fixedSizeMode(4);
            size = -1;
          }
        }
      };
      parser.setOutput(handler);
      socket.dataHandler(parser);
    });

    server.listen(asyncResult -> {
      if (asyncResult.succeeded()) {
        // Obtain system configured public host/port
        int publicPort = Integer.getInteger("vertx.cluster.public.port", -1);
        String publicHost = System.getProperty("vertx.cluster.public.host", null);

        // If using a wilcard port (0) then we ask the server for the actual port:
        int serverPort = (publicPort == -1) ? server.actualPort() : publicPort;
        String serverHost = (publicHost == null) ? hostName : publicHost;
        EventBusImpl.this.serverID = new ServerID(serverPort, serverHost);
      }
      if (listenHandler != null) {
        if (asyncResult.succeeded()) {
          listenHandler.handle(new FutureResultImpl<>((Void)null));
        } else {
          listenHandler.handle(new FutureResultImpl<>(asyncResult.cause()));
        }
      } else if (asyncResult.failed()) {
        log.error("Failed to listen", asyncResult.cause());
      }
    });
    return server;
  }

  private <T> void sendToSubs(ChoosableIterable<ServerID> subs, BaseMessage message,
                              long timeoutID,
                              Handler<AsyncResult<Message<T>>> asyncResultHandler,
                              Handler<Message<T>> replyHandler) {
    if (message.send) {
      // Choose one
      ServerID sid = subs.choose();
      if (!sid.equals(serverID)) {  //We don't send to this node
        sendRemote(sid, message);
      } else {
        receiveMessage(message, timeoutID, asyncResultHandler, replyHandler);
      }
    } else {
      // Publish
      for (ServerID sid : subs) {
        if (!sid.equals(serverID)) {  //We don't send to this node
          sendRemote(sid, message);
        } else {
          receiveMessage(message, timeoutID, null, replyHandler);
        }
      }
    }
  }

  private <T, U> void sendOrPubWithTimeout(BaseMessage<U> message,
                                           Handler<AsyncResult<Message<T>>> asyncResultHandler, long timeout) {
    Handler<Message<T>> handler = convertHandler(asyncResultHandler);
    sendOrPub(null, message, handler, asyncResultHandler, timeout);
  }

  private <T, U> void sendOrPub(BaseMessage<U> message, Handler<Message<T>> replyHandler) {
    sendOrPub(null, message, replyHandler, -1);
  }

  private <T, U> void sendOrPub(ServerID replyDest, BaseMessage<U> message, Handler<Message<T>> replyHandler, long timeout) {
    sendOrPub(replyDest, message, replyHandler, null, timeout);
  }

  private String generateReplyAddress() {
    if (clusterMgr != null) {
      // The address is a cryptographically secure id that can't be guessed
      return UUID.randomUUID().toString();
    } else {
      // Just use a sequence - it's faster
      return Long.toString(replySequence.incrementAndGet());
    }
  }

  private <T, U> void sendOrPub(ServerID replyDest, BaseMessage<U> message, Handler<Message<T>> replyHandler,
                                Handler<AsyncResult<Message<T>>> asyncResultHandler, long timeout) {
    checkStarted();
    ContextImpl context = vertx.getOrCreateContext();
    if (timeout == -1) {
      timeout = defaultReplyTimeout;
    }
    try {
      message.sender = serverID;
      long timeoutID = -1;
      if (replyHandler != null) {
        message.replyAddress = generateReplyAddress();
        Registration registration = registerHandler(message.replyAddress, replyHandler, true, true, timeoutID);
        if (timeout != -1) {
          // Add a timeout to remove the reply handler to prevent leaks in case a reply never comes
          timeoutID = vertx.setTimer(timeout, timerID -> {
            log.warn("Message reply handler timed out as no reply was received - it will be removed");
            registration.unregister();
            if (asyncResultHandler != null) {
              asyncResultHandler.handle(new FutureResultImpl<>(new ReplyException(ReplyFailure.TIMEOUT, "Timed out waiting for reply")));
            }
          });
        }
      }
      if (replyDest != null) {
        if (!replyDest.equals(this.serverID)) {
          sendRemote(replyDest, message);
        } else {
          receiveMessage(message, timeoutID, asyncResultHandler, replyHandler);
        }
      } else {
        if (subs != null) {
          long fTimeoutID = timeoutID;
          subs.get(message.address, asyncResult -> {
            if (asyncResult.succeeded()) {
              ChoosableIterable<ServerID> serverIDs = asyncResult.result();
              if (serverIDs != null && !serverIDs.isEmpty()) {
                sendToSubs(serverIDs, message, fTimeoutID, asyncResultHandler, replyHandler);
              } else {
                receiveMessage(message, fTimeoutID, asyncResultHandler, replyHandler);
              }
            } else {
              log.error("Failed to send message", asyncResult.cause());
            }
          });
        } else {
          // Not clustered
          receiveMessage(message, timeoutID, asyncResultHandler, replyHandler);
        }
      }

    } finally {
      // Reset the context id - send can cause messages to be delivered in different contexts so the context id
      // of the current thread can change
      if (context != null) {
        vertx.setContext(context);
      }
    }
  }

  private <T> Handler<Message<T>> convertHandler(Handler<AsyncResult<Message<T>>> handler) {
    return reply -> {
      FutureResultImpl<Message<T>> result;
      if (reply.body() instanceof ReplyException) {
        // This is kind of clunky - but hey-ho
        result = new FutureResultImpl<>((ReplyException)reply.body());
      } else {
        result = new FutureResultImpl<>(reply);
      }
      handler.handle(result);
    };
  }

  private Registration registerHandler(String address, Handler<? extends Message> handler,
                                       boolean replyHandler, boolean localOnly, long timeoutID) {
    checkStarted();
    if (address == null) {
      throw new NullPointerException("address");
    }
    if (handler == null) {
      throw new NullPointerException("handler");
    }
    ContextImpl context = vertx.getContext();
    boolean hasContext = context != null;
    if (!hasContext) {
      context = vertx.createEventLoopContext();
    }
    @SuppressWarnings("unchecked")
    HandlerHolder<?> holder = new HandlerHolder<>((Handler<Message<Object>>) handler, replyHandler, localOnly, context, timeoutID);
    HandlerRegistration registration = new HandlerRegistration(address, handler);

    Handlers handlers = handlerMap.get(address);
    if (handlers == null) {
      handlers = new Handlers();
      Handlers prevHandlers = handlerMap.putIfAbsent(address, handlers);
      if (prevHandlers != null) {
        handlers = prevHandlers;
      }
      if (subs != null && !replyHandler && !localOnly) {
        // Propagate the information
        subs.add(address, serverID, registration::setResult);
      } else {
        registration.result = new FutureResultImpl<>((Void) null);
      }
    } else {
      registration.result = new FutureResultImpl<>((Void) null);
    }

    handlers.list.add(holder);

    if (hasContext) {
      HandlerEntry entry = new HandlerEntry(address, handler);
      context.addCloseHook(entry);
    }

    return registration;
  }

  private void unregisterHandler(String address, Handler<? extends Message> handler, Handler<AsyncResult<Void>> completionHandler) {
    checkStarted();
    Handlers handlers = handlerMap.get(address);
    if (handlers != null) {
      synchronized (handlers) {
        int size = handlers.list.size();
        // Requires a list traversal. This is tricky to optimise since we can't use a set since
        // we need fast ordered traversal for the round robin
        for (int i = 0; i < size; i++) {
          HandlerHolder holder = handlers.list.get(i);
          if (holder.handler == handler) {
            if (holder.timeoutID != -1) {
              vertx.cancelTimer(holder.timeoutID);
            }
            handlers.list.remove(i);
            holder.removed = true;
            if (handlers.list.isEmpty()) {
              handlerMap.remove(address);
              if (subs != null && !holder.localOnly) {
                removeSub(address, serverID, completionHandler);
              } else if (completionHandler != null) {
                callCompletionHandler(completionHandler);
              }
            } else if (completionHandler != null) {
              callCompletionHandler(completionHandler);
            }
            holder.context.removeCloseHook(new HandlerEntry(address, handler));
            break;
          }
        }
      }
    }
  }

  private void unregisterHandler(String address, Handler<? extends Message> handler) {
    unregisterHandler(address, handler, emptyHandler());
  }

  private void callCompletionHandler(Handler<AsyncResult<Void>> completionHandler) {
    completionHandler.handle(new FutureResultImpl<>((Void) null));
  }

  private void cleanSubsForServerID(ServerID theServerID) {
    if (subs != null) {
      subs.removeAllForValue(theServerID, ar -> {
      });
    }
  }

  private void cleanupConnection(ServerID theServerID,
                                 ConnectionHolder holder,
                                 boolean failed) {
    if (holder.timeoutID != -1) {
      vertx.cancelTimer(holder.timeoutID);
    }
    if (holder.pingTimeoutID != -1) {
      vertx.cancelTimer(holder.pingTimeoutID);
    }
    try {
      holder.socket.close();
    } catch (Exception ignore) {
    }

    // The holder can be null or different if the target server is restarted with same serverid
    // before the cleanup for the previous one has been processed
    // So we only actually remove the entry if no new entry has been added
    if (connections.remove(theServerID, holder)) {
      log.debug("Cluster connection closed: " + theServerID + " holder " + holder);

      if (failed) {
        cleanSubsForServerID(theServerID);
      }
    }
  }

  private void sendRemote(ServerID theServerID, BaseMessage message) {
    // We need to deal with the fact that connecting can take some time and is async, and we cannot
    // block to wait for it. So we add any sends to a pending list if not connected yet.
    // Once we connect we send them.
    // This can also be invoked concurrently from different threads, so it gets a little
    // tricky
    ConnectionHolder holder = connections.get(theServerID);
    if (holder == null) {
      NetClient client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(60 * 1000));
      // When process is creating a lot of connections this can take some time
      // so increase the timeout
      holder = new ConnectionHolder(client);
      ConnectionHolder prevHolder = connections.putIfAbsent(theServerID, holder);
      if (prevHolder != null) {
        // Another one sneaked in
        holder = prevHolder;
      }
      else {
        holder.connect(client, theServerID);
      }
    }
    holder.writeMessage(message);
  }

  private void schedulePing(ConnectionHolder holder) {
    holder.pingTimeoutID = vertx.setTimer(PING_INTERVAL, id1 -> {
      // If we don't get a pong back in time we close the connection
      holder.timeoutID = vertx.setTimer(PING_REPLY_INTERVAL, id2 -> {
        // Didn't get pong in time - consider connection dead
        log.warn("No pong from server " + serverID + " - will consider it dead, timerID: " + id2 + " holder " + holder);
        cleanupConnection(holder.theServerID, holder, true);
      });
      new PingMessage(serverID).write(holder.socket);
    });
  }

  private void removeSub(String subName, ServerID theServerID, Handler<AsyncResult<Void>> completionHandler) {
    subs.remove(subName, theServerID, completionHandler);
  }

  // Called when a message is incoming
  private <T> void receiveMessage(BaseMessage msg, long timeoutID, Handler<AsyncResult<Message<T>>> asyncResultHandler,
                                  Handler<Message<T>> replyHandler) {
    msg.bus = this;
    Handlers handlers = handlerMap.get(msg.address);
    if (handlers != null) {
      if (msg.send) {
        //Choose one
        HandlerHolder holder = handlers.choose();
        if (holder != null) {
          doReceive(msg, holder);
        }
      } else {
        // Publish
        for (HandlerHolder holder: handlers.list) {
          doReceive(msg, holder);
        }
      }
    } else {
      // no handlers
      if (asyncResultHandler != null) {
        sendNoHandlersFailure(asyncResultHandler);
        if (timeoutID != -1) {
          vertx.cancelTimer(timeoutID);
        }
        if (replyHandler != null) {
          unregisterHandler(msg.replyAddress, replyHandler);
        }
      }
    }
  }

  private <T> void sendNoHandlersFailure(Handler<AsyncResult<Message<T>>> handler) {
    vertx.runOnContext(new Handler<Void>() {
      @Override
      public void handle(Void v) {
        handler.handle(new FutureResultImpl<>(new ReplyException(ReplyFailure.NO_HANDLERS)));
      }
    });
  }


  private <T> void doReceive(BaseMessage<T> msg, HandlerHolder<T> holder) {
    // Each handler gets a fresh copy
    Message<T> copied = msg.copy();

    holder.context.execute(() -> {
      // Need to check handler is still there - the handler might have been removed after the message were sent but
      // before it was received
      try {
        if (!holder.removed) {
          holder.handler.handle(copied);
        }
      } finally {
        if (holder.replyHandler) {
          unregisterHandler(msg.address, holder.handler);
        }
      }
    }, false);
  }

  private void checkStarted() {
    if (serverID == null) {
      throw new IllegalStateException("Event Bus is not started");
    }
  }

  private static final Handler<?> _emptyHandler = e -> {};

  @SuppressWarnings("unchecked")
  private static <T> Handler<T> emptyHandler() {
    return (Handler<T>) _emptyHandler;
  }

  private static class HandlerHolder<T> {
    final ContextImpl context;
    final Handler<Message<T>> handler;
    final boolean replyHandler;
    final boolean localOnly;
    final long timeoutID;
    boolean removed;

    HandlerHolder(Handler<Message<T>> handler, boolean replyHandler, boolean localOnly, ContextImpl context, long timeoutID) {
      this.context = context;
      this.handler = handler;
      this.replyHandler = replyHandler;
      this.localOnly = localOnly;
      this.timeoutID = timeoutID;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      HandlerHolder that = (HandlerHolder) o;
      return handler.equals(that.handler);
    }

    @Override
    public int hashCode() {
      return handler.hashCode();
    }

  }

  private class ConnectionHolder {
    final NetClient client;
    volatile NetSocket socket;
    final Queue<BaseMessage> pending = new ConcurrentLinkedQueue<>();
    volatile boolean connected;
    long timeoutID = -1;
    long pingTimeoutID = -1;
    ServerID theServerID;

    private ConnectionHolder(NetClient client) {
      this.client = client;
    }

    void writeMessage(BaseMessage message) {
      if (connected) {
        message.write(socket);
      } else {
        synchronized (this) {
          if (connected) {
            message.write(socket);
          } else {
            pending.add(message);
          }
        }
      }
    }

    synchronized void connected(ServerID theServerID, NetSocket socket) {
      this.socket = socket;
      this.theServerID = theServerID;
      connected = true;
      socket.exceptionHandler(t -> cleanupConnection(theServerID, ConnectionHolder.this, true));
      socket.closeHandler(v -> cleanupConnection(theServerID, ConnectionHolder.this, false));
      socket.dataHandler(data -> {
        // Got a pong back
        vertx.cancelTimer(timeoutID);
        schedulePing(ConnectionHolder.this);
      });
      // Start a pinger
      schedulePing(ConnectionHolder.this);
      for (BaseMessage message : pending) {
        message.write(socket);
      }
      pending.clear();
    }

    void connect(NetClient client, ServerID theServerID) {
      client.connect(theServerID.port, theServerID.host, res -> {
        if (res.succeeded()) {
          connected(theServerID, res.result());
        } else {
          cleanupConnection(theServerID, ConnectionHolder.this, true);
        }
      });
    }
  }

  private static class Handlers {

    final List<HandlerHolder> list = new CopyOnWriteArrayList<>();
    final AtomicInteger pos = new AtomicInteger(0);
    HandlerHolder choose() {
      while (true) {
        int size = list.size();
        if (size == 0) {
          return null;
        }
        int p = pos.getAndIncrement();
        if (p >= size - 1) {
          pos.set(0);
        }
        try {
          return list.get(p);
        } catch (IndexOutOfBoundsException e) {
          // Can happen
          pos.set(0);
        }
      }
    }
  }

  private class HandlerEntry implements Closeable {
    final String address;
    final Handler<? extends Message> handler;

    private HandlerEntry(String address, Handler<? extends Message> handler) {
      this.address = address;
      this.handler = handler;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (getClass() != o.getClass()) return false;
      HandlerEntry entry = (HandlerEntry) o;
      if (!address.equals(entry.address)) return false;
      if (!handler.equals(entry.handler)) return false;
      return true;
    }

    @Override
    public int hashCode() {
      int result = address != null ? address.hashCode() : 0;
      result = 31 * result + (handler != null ? handler.hashCode() : 0);
      return result;
    }

    // Called by context on undeploy
    public void close(Handler<AsyncResult<Void>> doneHandler) {
      unregisterHandler(this.address, this.handler, emptyHandler());
      doneHandler.handle(new FutureResultImpl<>((Void)null));
    }

  }

  @Override
  protected void finalize() throws Throwable {
    // Make sure this gets cleaned up if there are no more references to it
    // so as not to leave connections and resources dangling until the system is shutdown
    // which could make the JVM run out of file handles.
    close(ar -> {});
    super.finalize();
  }

  private class HandlerRegistration implements Registration {
    private final String address;
    private final Handler<? extends Message> handler;
    private AsyncResult<Void> result;
    private Handler<AsyncResult<Void>> completionHandler;

    public HandlerRegistration(String address, Handler<? extends Message> handler) {
      this.address = address;
      this.handler = handler;
    }

    @Override
    public String address() {
      return address;
    }

    @Override
    public synchronized void doneHandler(Handler<AsyncResult<Void>> completionHandler) {
      Objects.requireNonNull(completionHandler);
      if (result != null) {
        completionHandler.handle(result);
      } else {
        this.completionHandler = completionHandler;
      }
    }

    @Override
    public void unregister() {
      unregister(emptyHandler());
    }

    @Override
    public void unregister(Handler<AsyncResult<Void>> doneHandler) {
      Objects.requireNonNull(doneHandler);
      unregisterHandler(address, handler, doneHandler);
    }

    private synchronized void setResult(AsyncResult<Void> result) {
      this.result = result;
      if (completionHandler != null) {
        completionHandler.handle(result);
      } else if (result.failed()) {
        log.error("Failed to propagate registration for handler " + handler + " and address " + address);
      }
    }
  }
}

