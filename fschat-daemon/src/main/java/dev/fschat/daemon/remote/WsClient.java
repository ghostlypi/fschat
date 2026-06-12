package dev.fschat.daemon.remote;

import dev.fschat.daemon.queue.OfflineQueue;
import dev.fschat.protocol.Json;
import dev.fschat.protocol.wire.WsMessage;

import javax.net.ssl.SSLContext;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Maintains the single WebSocket connection to the server, surviving drops.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>connect, authenticate (first frame), and auto-reconnect with exponential backoff;</li>
 *   <li>flush the durable {@link OfflineQueue} of unacked write ops on every (re)connect
 *       (idempotent on the server via {@code clientOpId});</li>
 *   <li>app-level ping/pong keepalive to detect half-open sockets;</li>
 *   <li>dispatch every inbound message to a {@link Listener} (the SyncEngine), which decides
 *       what to (re)subscribe to on {@link Listener#onAuthOk}.</li>
 * </ul>
 *
 * The JDK {@link WebSocket} client has no built-in reconnect &mdash; that logic lives here.
 */
public final class WsClient implements AutoCloseable {

    /** Receives connection lifecycle and inbound protocol messages. */
    public interface Listener {
        void onAuthOk(WsMessage.AuthOk msg);

        void onEvent(WsMessage.Event msg);

        void onAck(WsMessage.Ack msg);

        void onBackfillDone(WsMessage.BackfillDone msg);

        void onChannelCreated(WsMessage.ChannelCreated msg);

        void onError(WsMessage.Error msg);

        default void onConnected() {
        }

        default void onDisconnected() {
        }
    }

    private static final Logger LOG = System.getLogger(WsClient.class.getName());
    private static final long INITIAL_BACKOFF_MS = 500;
    private static final long MAX_BACKOFF_MS = 10_000;
    private static final long PING_INTERVAL_S = 20;
    private static final long PONG_TIMEOUT_NS = Duration.ofSeconds(60).toNanos();

    private final URI serverUri;
    private final String token;
    private final Listener listener;
    private final OfflineQueue queue;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fschat-wsclient");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean running;
    private volatile boolean authed;
    private volatile WebSocket ws;
    private volatile long lastPongAt;
    private long backoffMs = INITIAL_BACKOFF_MS;
    private long pingNonce;

    private final Object sendLock = new Object();
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private final java.util.List<WsMessage> pendingControl = new java.util.ArrayList<>();
    private boolean reconnectScheduled;

    public WsClient(URI serverUri, String token, SSLContext sslContext, OfflineQueue queue, Listener listener) {
        this.serverUri = serverUri;
        this.token = token;
        this.listener = listener;
        this.queue = queue;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (sslContext != null) {
            b.sslContext(sslContext);
        }
        this.httpClient = b.build();
    }

    public void start() {
        running = true;
        scheduler.scheduleAtFixedRate(this::pingTick, PING_INTERVAL_S, PING_INTERVAL_S, TimeUnit.SECONDS);
        scheduler.execute(this::connect);
    }

    @Override
    public void close() {
        running = false;
        WebSocket socket = ws;
        if (socket != null) {
            socket.abort();
        }
        scheduler.shutdownNow();
    }

    public boolean isConnected() {
        return authed && ws != null;
    }

    // ----- outbound API -----------------------------------------------------

    /** Subscribe to a channel from a given seq (called by the SyncEngine on auth/reconnect). */
    public void subscribe(String channelId, long fromSeq) {
        sendIfReady(new WsMessage.Subscribe(channelId, fromSeq));
    }

    /** Durably enqueue a write op and send it if connected; otherwise it flushes on reconnect. */
    public void enqueueOp(WsMessage.Op op) {
        queue.add(op);
        if (isReady()) {
            sendRaw(Json.write(op));
        }
    }

    /**
     * Send a control message (create/add-member). If not yet authenticated, it
     * is buffered and flushed on the next {@code auth_ok}, so a command issued
     * immediately after {@code start} (before the WS handshake completes) is not
     * lost.
     */
    public void sendControl(WsMessage msg) {
        synchronized (sendLock) {
            if (isReady()) {
                sendRaw(Json.write(msg));
            } else {
                pendingControl.add(msg);
            }
        }
    }

    // ----- connection lifecycle --------------------------------------------

    private void connect() {
        if (!running) {
            return;
        }
        authed = false;
        resetSendChain();
        try {
            WebSocket socket = httpClient.newWebSocketBuilder()
                    .buildAsync(serverUri, new Handler())
                    .get(15, TimeUnit.SECONDS);
            this.ws = socket;
            this.backoffMs = INITIAL_BACKOFF_MS;
            this.lastPongAt = System.nanoTime();
            listener.onConnected();
            sendRaw(Json.write(new WsMessage.Auth(token)));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "connect to " + serverUri + " failed: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private synchronized void scheduleReconnect() {
        if (!running || reconnectScheduled) {
            return;
        }
        reconnectScheduled = true;
        WebSocket old = ws;
        ws = null;
        authed = false;
        if (old != null) {
            old.abort();
        }
        listener.onDisconnected();
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        scheduler.schedule(() -> {
            synchronized (this) {
                reconnectScheduled = false;
            }
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void pingTick() {
        if (!isReady()) {
            return;
        }
        if (System.nanoTime() - lastPongAt > PONG_TIMEOUT_NS) {
            LOG.log(Level.WARNING, "no pong within timeout; reconnecting");
            scheduleReconnect();
            return;
        }
        sendRaw(Json.write(new WsMessage.Ping(++pingNonce)));
    }

    private void dispatch(WsMessage msg) {
        switch (msg) {
            case WsMessage.AuthOk a -> {
                authed = true;
                listener.onAuthOk(a);
                flushQueue();
                flushControl();
            }
            case WsMessage.Ack ack -> {
                queue.remove(ack.clientOpId());
                listener.onAck(ack);
            }
            case WsMessage.Event e -> listener.onEvent(e);
            case WsMessage.BackfillDone b -> listener.onBackfillDone(b);
            case WsMessage.ChannelCreated cc -> listener.onChannelCreated(cc);
            case WsMessage.Error err -> listener.onError(err);
            case WsMessage.Pong ignored -> lastPongAt = System.nanoTime();
            default -> { /* server-only message types are not expected inbound here */ }
        }
    }

    private void flushQueue() {
        List<WsMessage.Op> ops = queue.snapshot();
        for (WsMessage.Op op : ops) {
            sendRaw(Json.write(op));
        }
    }

    private void flushControl() {
        List<WsMessage> toSend;
        synchronized (sendLock) {
            toSend = new java.util.ArrayList<>(pendingControl);
            pendingControl.clear();
        }
        for (WsMessage msg : toSend) {
            sendRaw(Json.write(msg));
        }
    }

    // ----- send plumbing ----------------------------------------------------

    private boolean isReady() {
        return authed && ws != null;
    }

    private void sendIfReady(WsMessage msg) {
        if (isReady()) {
            sendRaw(Json.write(msg));
        }
    }

    private void resetSendChain() {
        synchronized (sendLock) {
            sendChain = CompletableFuture.completedFuture(null);
        }
    }

    /** Chain sends so we never call sendText before the previous send completes. */
    private void sendRaw(String text) {
        synchronized (sendLock) {
            sendChain = sendChain
                    .handle((v, e) -> null)
                    .thenCompose(v -> {
                        WebSocket socket = ws;
                        return socket == null
                                ? CompletableFuture.completedFuture(null)
                                : socket.sendText(text, true);
                    });
        }
    }

    /** Per-connection inbound handler (its own text buffer, so connections never bleed). */
    private final class Handler implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String json = buf.toString();
                buf.setLength(0);
                try {
                    dispatch(Json.read(json, WsMessage.class));
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "failed to handle inbound message: " + e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            scheduleReconnect();
        }
    }
}
