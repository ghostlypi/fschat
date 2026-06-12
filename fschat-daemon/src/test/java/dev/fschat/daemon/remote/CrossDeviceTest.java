package dev.fschat.daemon.remote;

import dev.fschat.daemon.queue.OfflineQueue;
import dev.fschat.protocol.model.ChannelType;
import dev.fschat.protocol.model.EventType;
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

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies cross-device support: the SAME account connected from two independent
 * daemons (separate offline queues) sees each other's messages and converges on
 * edits. Exercises the multi-connection fanout path
 * ({@code ConnectionRegistry} keyed by userId, {@code FschatWsServer.fanout}).
 */
class CrossDeviceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tmp;
    private Db serverDb;
    private FschatWsServer server;
    private String aliceToken;
    private String channelId;
    private final List<WsClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        serverDb = new Db(tmp.resolve("server.db"));
        UserStore users = new UserStore(serverDb);
        ChannelStore channels = new ChannelStore(serverDb);
        AuthService auth = new AuthService(users, new Tokens(SECRET, 3600));
        ChannelService channelService = new ChannelService(users, channels, new EventLog(serverDb), new dev.fschat.server.store.BlockStore(serverDb));
        server = new FschatWsServer(new InetSocketAddress("127.0.0.1", 0), null, auth, channelService);
        server.start();
        server.awaitStart(5, TimeUnit.SECONDS);

        aliceToken = auth.register("alice", "password1").token();
        auth.register("bob", "password1");
        // Pre-create the DM so both of alice's devices learn it via auth_ok.
        String aliceId = users.findByUsername("alice").orElseThrow().id();
        String bobId = users.findByUsername("bob").orElseThrow().id();
        channelId = channels.create(ChannelType.DM, null, List.of(aliceId, bobId)).id();
    }

    @AfterEach
    void tearDown() throws Exception {
        clients.forEach(WsClient::close);
        server.stop(1000);
        serverDb.close();
    }

    private Device connectAliceDevice(String name) throws Exception {
        Device d = new Device();
        WsClient client = new WsClient(URI.create("ws://127.0.0.1:" + server.getPort()), aliceToken, null,
                new OfflineQueue(tmp.resolve(name + "-queue.json")), d);
        d.bind(client);
        clients.add(client);
        client.start();
        d.awaitAuthOk(5_000);
        return d;
    }

    @Test
    void sameAccountTwoDevicesSeeEachOtherAndConverge() throws Exception {
        Device deviceA = connectAliceDevice("A");
        Device deviceB = connectAliceDevice("B");

        // Device A posts -> Device B (same account, other daemon) receives it live.
        deviceA.client.enqueueOp(new WsMessage.Op("opA", channelId, OpType.POST, null, "from device A"));
        WsMessage.Event onB = deviceB.awaitEvent(e -> "from device A".equals(e.content()), 5_000);
        assertThat(onB.authorName()).isEqualTo("alice");
        // ...and Device A sees its own echo (server is the source of truth).
        deviceA.awaitEvent(e -> "from device A".equals(e.content()), 5_000);

        // Reverse direction works too.
        deviceB.client.enqueueOp(new WsMessage.Op("opB", channelId, OpType.POST, null, "from device B"));
        deviceA.awaitEvent(e -> "from device B".equals(e.content()), 5_000);

        // Convergence: an edit on A propagates to B.
        deviceA.client.enqueueOp(new WsMessage.Op("opE", channelId, OpType.EDIT, onB.messageId(), "edited on A"));
        WsMessage.Event editOnB = deviceB.awaitEvent(
                e -> e.type() == EventType.EDIT && "edited on A".equals(e.content()), 5_000);
        assertThat(editOnB.messageId()).isEqualTo(onB.messageId());
    }

    /** A device: subscribes to every channel on auth, captures inbound events. */
    private static final class Device implements WsClient.Listener {
        final BlockingQueue<WsMessage.Event> events = new LinkedBlockingQueue<>();
        private final Map<String, Long> lastSeq = new ConcurrentHashMap<>();
        WsClient client;
        private int authOkCount;

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
        public void onEvent(WsMessage.Event msg) {
            lastSeq.merge(msg.channelId(), msg.seq(), Math::max);
            events.add(msg);
        }

        @Override
        public void onAck(WsMessage.Ack msg) {
        }

        @Override
        public void onBackfillDone(WsMessage.BackfillDone msg) {
        }

        @Override
        public void onChannelCreated(WsMessage.ChannelCreated msg) {
            client.subscribe(msg.channel().id(), lastSeq.getOrDefault(msg.channel().id(), 0L));
        }

        @Override
        public void onError(WsMessage.Error msg) {
        }

        synchronized void awaitAuthOk(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (authOkCount < 1) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new AssertionError("timed out waiting for auth_ok");
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
