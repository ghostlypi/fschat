package dev.fschat.daemon.remote;

import dev.fschat.daemon.queue.OfflineQueue;
import dev.fschat.protocol.model.ChannelType;
import dev.fschat.protocol.model.OpType;
import dev.fschat.protocol.wire.WsMessage;
import dev.fschat.server.auth.AuthService;
import dev.fschat.server.auth.Tokens;
import dev.fschat.server.channel.ChannelService;
import dev.fschat.server.log.EventLog;
import dev.fschat.server.net.FschatWsServer;
import dev.fschat.server.store.ChannelStore;
import dev.fschat.server.store.Db;
import dev.fschat.server.store.UserStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class WsClientTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tmp;
    private Path dbFile;
    private FschatWsServer server;
    private Db serverDb;
    private AuthService auth;
    private WsClient client;

    @BeforeEach
    void setUp() {
        dbFile = tmp.resolve("server.db");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        stopServer();
    }

    private void startServer(int port) throws Exception {
        serverDb = new Db(dbFile);
        UserStore users = new UserStore(serverDb);
        ChannelStore channels = new ChannelStore(serverDb);
        EventLog log = new EventLog(serverDb);
        auth = new AuthService(users, new Tokens(SECRET, 3600));
        ChannelService channelService = new ChannelService(users, channels, log, new dev.fschat.server.store.BlockStore(serverDb));
        server = new FschatWsServer(new InetSocketAddress("127.0.0.1", port), null, auth, channelService);
        server.start();
        assertThat(server.awaitStart(5, TimeUnit.SECONDS)).isTrue();
    }

    private void stopServer() throws Exception {
        if (server != null) {
            server.stop(1000);
            server = null;
        }
        if (serverDb != null) {
            serverDb.close();
            serverDb = null;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void connectsAuthenticatesCreatesPostsAndReceives() throws Exception {
        int port = freePort();
        startServer(port);
        String token = auth.register("alice", "password1").token();
        auth.register("bob", "password1");

        Listener listener = new Listener();
        client = new WsClient(URI.create("ws://127.0.0.1:" + port), token, null,
                new OfflineQueue(tmp.resolve("q.json")), listener);
        listener.bind(client);
        client.start();

        listener.awaitAuthOk(1, 5_000);

        client.sendControl(new WsMessage.CreateChannel("req1", ChannelType.DM, null, List.of("bob")));
        WsMessage.ChannelCreated created = listener.created.poll(5, TimeUnit.SECONDS);
        assertThat(created).isNotNull();
        String channelId = created.channel().id();

        client.enqueueOp(new WsMessage.Op("op1", channelId, OpType.POST, null, "hello"));
        WsMessage.Ack ack = listener.acks.poll(5, TimeUnit.SECONDS);
        assertThat(ack).isNotNull();
        assertThat(ack.clientOpId()).isEqualTo("op1");

        WsMessage.Event ev = listener.awaitEvent(e -> "hello".equals(e.content()), 5_000);
        assertThat(ev.authorName()).isEqualTo("alice");
    }

    @Test
    void reconnectsResubscribesAndFlushesOfflineQueue() throws Exception {
        int port = freePort();
        startServer(port);
        String token = auth.register("alice", "password1").token();
        auth.register("bob", "password1");

        OfflineQueue queue = new OfflineQueue(tmp.resolve("q.json"));
        Listener listener = new Listener();
        client = new WsClient(URI.create("ws://127.0.0.1:" + port), token, null, queue, listener);
        listener.bind(client);
        client.start();
        listener.awaitAuthOk(1, 5_000);

        client.sendControl(new WsMessage.CreateChannel("req1", ChannelType.DM, null, List.of("bob")));
        WsMessage.ChannelCreated created = listener.created.poll(5, TimeUnit.SECONDS);
        assertThat(created).isNotNull();
        String channelId = created.channel().id();

        client.enqueueOp(new WsMessage.Op("op_before", channelId, OpType.POST, null, "before"));
        listener.awaitEvent(e -> "before".equals(e.content()), 5_000);

        // Server goes down.
        stopServer();
        listener.awaitDisconnect(1, 10_000);

        // Issue an op while disconnected; it must be queued, not lost.
        client.enqueueOp(new WsMessage.Op("op_down", channelId, OpType.POST, null, "during-down"));
        assertThat(queue.snapshot()).extracting(WsMessage.Op::clientOpId).contains("op_down");

        // Server comes back on the same port + same DB.
        startServer(port);

        // The client reconnects, re-subscribes (resume), and flushes the queued op exactly once.
        WsMessage.Event recovered = listener.awaitEvent(e -> "during-down".equals(e.content()), 20_000);
        assertThat(recovered).isNotNull();

        // The flushed op is acked and removed from the durable queue.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(queue.isEmpty()).isTrue();
    }

    /** Test listener: captures messages and re-subscribes from the last seen seq on (re)connect. */
    private static final class Listener implements WsClient.Listener {
        final BlockingQueue<WsMessage.Event> events = new LinkedBlockingQueue<>();
        final BlockingQueue<WsMessage.Ack> acks = new LinkedBlockingQueue<>();
        final BlockingQueue<WsMessage.ChannelCreated> created = new LinkedBlockingQueue<>();
        final BlockingQueue<WsMessage.Error> errors = new LinkedBlockingQueue<>();
        private final Map<String, Long> lastSeq = new ConcurrentHashMap<>();
        private volatile WsClient client;
        private int authOkCount;
        private int disconnectCount;

        void bind(WsClient client) {
            this.client = client;
        }

        @Override
        public synchronized void onAuthOk(WsMessage.AuthOk msg) {
            authOkCount++;
            for (var ch : msg.channels()) {
                client.subscribe(ch.id(), lastSeq.getOrDefault(ch.id(), 0L));
            }
            notifyAll();
        }

        @Override
        public void onChannelCreated(WsMessage.ChannelCreated msg) {
            client.subscribe(msg.channel().id(), lastSeq.getOrDefault(msg.channel().id(), 0L));
            created.add(msg);
        }

        @Override
        public void onEvent(WsMessage.Event msg) {
            lastSeq.merge(msg.channelId(), msg.seq(), Math::max);
            events.add(msg);
        }

        @Override
        public void onAck(WsMessage.Ack msg) {
            acks.add(msg);
        }

        @Override
        public void onBackfillDone(WsMessage.BackfillDone msg) {
        }

        @Override
        public void onError(WsMessage.Error msg) {
            errors.add(msg);
        }

        @Override
        public synchronized void onDisconnected() {
            disconnectCount++;
            notifyAll();
        }

        synchronized void awaitAuthOk(int count, long timeoutMs) throws InterruptedException {
            awaitCount(() -> authOkCount, count, timeoutMs, "authOk");
        }

        synchronized void awaitDisconnect(int count, long timeoutMs) throws InterruptedException {
            awaitCount(() -> disconnectCount, count, timeoutMs, "disconnect");
        }

        private synchronized void awaitCount(java.util.function.IntSupplier get, int n, long timeoutMs, String what)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (get.getAsInt() < n) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new AssertionError("timed out waiting for " + n + " " + what);
                }
                wait(remaining);
            }
        }

        WsMessage.Event awaitEvent(Predicate<WsMessage.Event> match, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                WsMessage.Event e = events.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (e != null && match.test(e)) {
                    return e;
                }
            }
            throw new AssertionError("timed out waiting for matching event");
        }
    }
}
