package dev.fschat.server.net;

import dev.fschat.protocol.Json;
import dev.fschat.protocol.model.ChannelType;
import dev.fschat.protocol.model.EventType;
import dev.fschat.protocol.model.OpType;
import dev.fschat.protocol.wire.WsMessage;
import dev.fschat.server.auth.AuthService;
import dev.fschat.server.auth.Tokens;
import dev.fschat.server.channel.ChannelException;
import dev.fschat.server.channel.ChannelService;
import dev.fschat.server.log.EventLog;
import dev.fschat.server.store.ChannelStore;
import dev.fschat.server.store.Db;
import dev.fschat.server.store.UserStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FschatWsServerTest {

    private Db db;
    private FschatWsServer server;
    private AuthService auth;
    private int port;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        db = new Db(tmp.resolve("ws.db"));
        UserStore users = new UserStore(db);
        ChannelStore channels = new ChannelStore(db);
        EventLog log = new EventLog(db);
        auth = new AuthService(users, new Tokens("0123456789abcdef0123456789abcdef", 3600));
        ChannelService channelService = new ChannelService(users, channels, log, new dev.fschat.server.store.BlockStore(db));

        aliceToken = auth.register("alice", "password1").token();
        bobToken = auth.register("bob", "password1").token();

        server = new FschatWsServer(new InetSocketAddress("127.0.0.1", 0), null, auth, channelService);
        server.start();
        assertThat(server.awaitStart(5, TimeUnit.SECONDS)).isTrue();
        port = server.getPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(1000);
        db.close();
    }

    @Test
    void unauthenticatedOpIsRefused() {
        try (TestClient c = TestClient.connect(port)) {
            c.send(new WsMessage.Op("op_1", "ch_x", OpType.POST, null, "hi"));
            WsMessage.Error err = c.expect(WsMessage.Error.class);
            assertThat(err.code()).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void badTokenIsRejected() {
        try (TestClient c = TestClient.connect(port)) {
            c.send(new WsMessage.Auth("not-a-real-token"));
            assertThat(c.expect(WsMessage.Error.class).code()).isEqualTo("INVALID_TOKEN");
        }
    }

    @Test
    void createSubscribePostAndAck() {
        try (TestClient alice = TestClient.connect(port)) {
            alice.send(new WsMessage.Auth(aliceToken));
            assertThat(alice.expect(WsMessage.AuthOk.class).username()).isEqualTo("alice");

            alice.send(new WsMessage.CreateChannel("req_1", ChannelType.DM, null, java.util.List.of("bob")));
            WsMessage.ChannelCreated created = alice.expect(WsMessage.ChannelCreated.class);
            String channelId = created.channel().id();
            assertThat(created.channel().type()).isEqualTo(ChannelType.DM);

            alice.send(new WsMessage.Subscribe(channelId, 0));
            assertThat(alice.expect(WsMessage.BackfillDone.class).headSeq()).isEqualTo(0L);

            alice.send(new WsMessage.Op("op_1", channelId, OpType.POST, null, "hello bob"));
            WsMessage.Ack ack = alice.expect(WsMessage.Ack.class);
            assertThat(ack.clientOpId()).isEqualTo("op_1");
            assertThat(ack.seq()).isEqualTo(1L);

            WsMessage.Event ev = alice.expect(WsMessage.Event.class);
            assertThat(ev.type()).isEqualTo(EventType.POST);
            assertThat(ev.content()).isEqualTo("hello bob");
            assertThat(ev.authorName()).isEqualTo("alice");
            assertThat(ev.messageId()).isEqualTo(ack.messageId());
        }
    }

    @Test
    void liveFanoutAndOwnershipEnforcement() {
        try (TestClient alice = TestClient.connect(port);
             TestClient bob = TestClient.connect(port)) {
            // alice sets up a DM and posts a message
            alice.send(new WsMessage.Auth(aliceToken));
            alice.expect(WsMessage.AuthOk.class);
            alice.send(new WsMessage.CreateChannel("req_1", ChannelType.DM, null, java.util.List.of("bob")));
            String channelId = alice.expect(WsMessage.ChannelCreated.class).channel().id();
            alice.send(new WsMessage.Subscribe(channelId, 0));
            alice.expect(WsMessage.BackfillDone.class);
            alice.send(new WsMessage.Op("op_1", channelId, OpType.POST, null, "first"));
            String aliceMsgId = alice.expect(WsMessage.Ack.class).messageId();
            alice.expect(WsMessage.Event.class); // own post echoed

            // bob connects; AuthOk should list the DM; subscribing backfills the post
            bob.send(new WsMessage.Auth(bobToken));
            assertThat(bob.expect(WsMessage.AuthOk.class).channels())
                    .anyMatch(ch -> ch.id().equals(channelId));
            bob.send(new WsMessage.Subscribe(channelId, 0));
            assertThat(bob.expect(WsMessage.Event.class).content()).isEqualTo("first");
            bob.expect(WsMessage.BackfillDone.class);

            // alice posts again -> bob receives it live via fanout
            alice.send(new WsMessage.Op("op_2", channelId, OpType.POST, null, "second"));
            alice.expect(WsMessage.Ack.class);
            alice.expect(WsMessage.Event.class);
            assertThat(bob.expect(WsMessage.Event.class).content()).isEqualTo("second");

            // bob cannot edit alice's message
            bob.send(new WsMessage.Op("op_b", channelId, OpType.EDIT, aliceMsgId, "hacked"));
            WsMessage.Error err = bob.expect(WsMessage.Error.class);
            assertThat(err.code()).isEqualTo(ChannelException.NOT_OWNER);
            assertThat(err.refId()).isEqualTo("op_b");

            // alice can edit her own message; both observe the EDIT
            alice.send(new WsMessage.Op("op_3", channelId, OpType.EDIT, aliceMsgId, "first (edited)"));
            alice.expect(WsMessage.Ack.class);
            assertThat(alice.expect(WsMessage.Event.class).type()).isEqualTo(EventType.EDIT);
            WsMessage.Event bobSees = bob.expect(WsMessage.Event.class);
            assertThat(bobSees.type()).isEqualTo(EventType.EDIT);
            assertThat(bobSees.content()).isEqualTo("first (edited)");
        }
    }

    @Test
    void duplicateOpIsAckedbutNotRefanned() {
        try (TestClient alice = TestClient.connect(port)) {
            alice.send(new WsMessage.Auth(aliceToken));
            alice.expect(WsMessage.AuthOk.class);
            alice.send(new WsMessage.CreateChannel("req_1", ChannelType.DM, null, java.util.List.of("bob")));
            String channelId = alice.expect(WsMessage.ChannelCreated.class).channel().id();
            alice.send(new WsMessage.Subscribe(channelId, 0));
            alice.expect(WsMessage.BackfillDone.class);

            alice.send(new WsMessage.Op("op_dup", channelId, OpType.POST, null, "once"));
            WsMessage.Ack ack1 = alice.expect(WsMessage.Ack.class);
            alice.expect(WsMessage.Event.class);

            // resend same clientOpId -> same ack, no new event
            alice.send(new WsMessage.Op("op_dup", channelId, OpType.POST, null, "once-resent"));
            WsMessage.Ack ack2 = alice.expect(WsMessage.Ack.class);
            assertThat(ack2.seq()).isEqualTo(ack1.seq());
            assertThat(ack2.messageId()).isEqualTo(ack1.messageId());
            assertThat(alice.pollWithin(500, TimeUnit.MILLISECONDS)).isNull(); // no second Event
        }
    }

    @Test
    void oversizedMessageIsRejected() {
        try (TestClient alice = TestClient.connect(port)) {
            alice.send(new WsMessage.Auth(aliceToken));
            alice.expect(WsMessage.AuthOk.class);
            alice.send(new WsMessage.CreateChannel("req1", ChannelType.DM, null, java.util.List.of("bob")));
            String channelId = alice.expect(WsMessage.ChannelCreated.class).channel().id();
            alice.send(new WsMessage.Subscribe(channelId, 0));
            alice.expect(WsMessage.BackfillDone.class);

            String huge = "x".repeat(ChannelService.MAX_CONTENT_CHARS + 1);
            alice.send(new WsMessage.Op("op_big", channelId, OpType.POST, null, huge));
            assertThat(alice.expect(WsMessage.Error.class).code()).isEqualTo(ChannelException.BAD_REQUEST);
        }
    }

    @Test
    void groupChatFanoutAndAddMember() {
        String carolToken = auth.register("carol", "password1").token();
        auth.register("dave", "password1");
        try (TestClient alice = TestClient.connect(port);
             TestClient bob = TestClient.connect(port);
             TestClient carol = TestClient.connect(port)) {
            alice.send(new WsMessage.Auth(aliceToken));
            alice.expect(WsMessage.AuthOk.class);
            bob.send(new WsMessage.Auth(bobToken));
            bob.expect(WsMessage.AuthOk.class);
            carol.send(new WsMessage.Auth(carolToken));
            carol.expect(WsMessage.AuthOk.class);

            // alice creates a group with bob and carol
            alice.send(new WsMessage.CreateChannel("req1", ChannelType.GROUP, "trip",
                    java.util.List.of("bob", "carol")));
            WsMessage.ChannelCreated created = alice.expect(WsMessage.ChannelCreated.class);
            String channelId = created.channel().id();
            assertThat(created.channel().members()).hasSize(3);
            // bob and carol get pushed the new channel so their daemons can subscribe
            assertThat(bob.expectEventually(WsMessage.ChannelCreated.class).channel().id()).isEqualTo(channelId);
            assertThat(carol.expectEventually(WsMessage.ChannelCreated.class).channel().id()).isEqualTo(channelId);

            for (TestClient c : java.util.List.of(alice, bob, carol)) {
                c.send(new WsMessage.Subscribe(channelId, 0));
                c.expectEventually(WsMessage.BackfillDone.class);
            }

            // alice posts -> all three see it
            alice.send(new WsMessage.Op("op1", channelId, OpType.POST, null, "hi team"));
            assertThat(alice.expectEvent(EventType.POST).content()).isEqualTo("hi team");
            assertThat(bob.expectEvent(EventType.POST).content()).isEqualTo("hi team");
            assertThat(carol.expectEvent(EventType.POST).content()).isEqualTo("hi team");

            // alice adds dave -> a MEMBER_ADD event fans out
            alice.send(new WsMessage.AddMember("req2", channelId, "dave"));
            WsMessage.Event memberAdd = bob.expectEvent(EventType.MEMBER_ADD);
            assertThat(memberAdd.content()).isEqualTo("dave");
            assertThat(carol.expectEvent(EventType.MEMBER_ADD).content()).isEqualTo("dave");
        }
    }

    @Test
    void blockingStopsFanoutBackfillAndDmCreation() {
        try (TestClient alice = TestClient.connect(port);
             TestClient bob = TestClient.connect(port)) {
            alice.send(new WsMessage.Auth(aliceToken));
            alice.expect(WsMessage.AuthOk.class);
            bob.send(new WsMessage.Auth(bobToken));
            String bobId = bob.expect(WsMessage.AuthOk.class).userId();

            alice.send(new WsMessage.CreateChannel("r1", ChannelType.DM, null, java.util.List.of("bob")));
            String channelId = alice.expect(WsMessage.ChannelCreated.class).channel().id();
            bob.expectEventually(WsMessage.ChannelCreated.class);
            alice.send(new WsMessage.Subscribe(channelId, 0));
            alice.expectEventually(WsMessage.BackfillDone.class);
            bob.send(new WsMessage.Subscribe(channelId, 0));
            bob.expectEventually(WsMessage.BackfillDone.class);

            // baseline: alice's own message + one from bob, both before the block
            alice.send(new WsMessage.Op("opa", channelId, OpType.POST, null, "ally"));
            assertThat(alice.expectEvent(EventType.POST).content()).isEqualTo("ally");
            bob.send(new WsMessage.Op("opb0", channelId, OpType.POST, null, "before block"));
            assertThat(alice.expectEvent(EventType.POST).content()).isEqualTo("before block");

            // alice blocks bob
            alice.send(new WsMessage.Block("rb", bobId));
            assertThat(alice.expectEventually(WsMessage.BlockAck.class).blocked()).isTrue();

            // bob posts again -> alice receives nothing (fanout skipped)
            bob.send(new WsMessage.Op("opb1", channelId, OpType.POST, null, "after block"));
            bob.expectEvent(EventType.POST); // bob still sees his own
            assertThat(alice.pollWithin(500, TimeUnit.MILLISECONDS)).isNull();

            // a fresh alice connection backfilling omits ALL of bob's messages
            try (TestClient alice2 = TestClient.connect(port)) {
                alice2.send(new WsMessage.Auth(aliceToken));
                alice2.expect(WsMessage.AuthOk.class);
                alice2.send(new WsMessage.Subscribe(channelId, 0));
                java.util.List<String> seen = new java.util.ArrayList<>();
                WsMessage m;
                while (!((m = alice2.pollWithin(2, TimeUnit.SECONDS)) instanceof WsMessage.BackfillDone)) {
                    assertThat(m).as("backfill ended unexpectedly").isNotNull();
                    if (m instanceof WsMessage.Event e) {
                        seen.add(e.content());
                    }
                }
                assertThat(seen).contains("ally").doesNotContain("before block", "after block");
            }

            // bob can no longer create/open a DM with alice
            bob.send(new WsMessage.CreateChannel("rb2", ChannelType.DM, null, java.util.List.of("alice")));
            assertThat(bob.expectEventually(WsMessage.Error.class).code()).isEqualTo(ChannelException.USER_NOT_FOUND);
        }
    }

    @Test
    void renameGroupFansOutAndDmRenameRejected() {
        try (TestClient alice = TestClient.connect(port);
             TestClient bob = TestClient.connect(port)) {
            alice.send(new WsMessage.Auth(aliceToken));
            alice.expect(WsMessage.AuthOk.class);
            bob.send(new WsMessage.Auth(bobToken));
            bob.expect(WsMessage.AuthOk.class);

            alice.send(new WsMessage.CreateChannel("r1", ChannelType.GROUP, "trip", java.util.List.of("bob")));
            String gid = alice.expect(WsMessage.ChannelCreated.class).channel().id();
            bob.expectEventually(WsMessage.ChannelCreated.class);
            alice.send(new WsMessage.Subscribe(gid, 0));
            alice.expectEventually(WsMessage.BackfillDone.class);
            bob.send(new WsMessage.Subscribe(gid, 0));
            bob.expectEventually(WsMessage.BackfillDone.class);

            alice.send(new WsMessage.Rename("rr", gid, "weekend"));
            assertThat(alice.expectEvent(EventType.RENAME).content()).isEqualTo("weekend");
            assertThat(bob.expectEvent(EventType.RENAME).content()).isEqualTo("weekend");

            // renaming a DM is rejected
            alice.send(new WsMessage.CreateChannel("r2", ChannelType.DM, null, java.util.List.of("bob")));
            String dmId = alice.expectEventually(WsMessage.ChannelCreated.class).channel().id();
            alice.send(new WsMessage.Rename("rr2", dmId, "nope"));
            assertThat(alice.expectEventually(WsMessage.Error.class).code()).isEqualTo(ChannelException.BAD_REQUEST);
        }
    }

    @Test
    void blockingDoesNotAffectGroups() {
        try (TestClient alice = TestClient.connect(port);
             TestClient bob = TestClient.connect(port)) {
            alice.send(new WsMessage.Auth(aliceToken));
            alice.expect(WsMessage.AuthOk.class);
            bob.send(new WsMessage.Auth(bobToken));
            String bobId = bob.expect(WsMessage.AuthOk.class).userId();

            alice.send(new WsMessage.CreateChannel("r1", ChannelType.GROUP, "g", java.util.List.of("bob")));
            String gid = alice.expect(WsMessage.ChannelCreated.class).channel().id();
            bob.expectEventually(WsMessage.ChannelCreated.class);
            alice.send(new WsMessage.Subscribe(gid, 0));
            alice.expectEventually(WsMessage.BackfillDone.class);
            bob.send(new WsMessage.Subscribe(gid, 0));
            bob.expectEventually(WsMessage.BackfillDone.class);

            // alice blocks bob, but they share a GROUP
            alice.send(new WsMessage.Block("rb", bobId));
            alice.expectEventually(WsMessage.BlockAck.class);

            // bob's group message still reaches alice (blocking is DM-only)
            bob.send(new WsMessage.Op("opb", gid, OpType.POST, null, "still visible"));
            assertThat(alice.expectEvent(EventType.POST).content()).isEqualTo("still visible");
        }
    }

    @Test
    void blockedUserCannotAddYouToAGroup() {
        try (TestClient alice = TestClient.connect(port);
             TestClient bob = TestClient.connect(port)) {
            alice.send(new WsMessage.Auth(aliceToken));
            alice.expect(WsMessage.AuthOk.class);
            bob.send(new WsMessage.Auth(bobToken));
            String bobId = bob.expect(WsMessage.AuthOk.class).userId();

            // alice blocks bob
            alice.send(new WsMessage.Block("rb", bobId));
            alice.expectEventually(WsMessage.BlockAck.class);

            // bob cannot create a group that includes alice (who blocked him)
            bob.send(new WsMessage.CreateChannel("r1", ChannelType.GROUP, "g", java.util.List.of("alice")));
            assertThat(bob.expectEventually(WsMessage.Error.class).code()).isEqualTo(ChannelException.USER_NOT_FOUND);

            // ...and cannot add alice to a group he already has
            bob.send(new WsMessage.CreateChannel("r2", ChannelType.GROUP, "solo", java.util.List.of()));
            String gid = bob.expectEventually(WsMessage.ChannelCreated.class).channel().id();
            bob.send(new WsMessage.AddMember("r3", gid, "alice"));
            assertThat(bob.expectEventually(WsMessage.Error.class).code()).isEqualTo(ChannelException.USER_NOT_FOUND);
        }
    }

    @Test
    void removeMemberFansLeaveAndStopsDelivery() {
        try (TestClient alice = TestClient.connect(port);
             TestClient bob = TestClient.connect(port)) {
            alice.send(new WsMessage.Auth(aliceToken));
            alice.expect(WsMessage.AuthOk.class);
            bob.send(new WsMessage.Auth(bobToken));
            bob.expect(WsMessage.AuthOk.class);

            alice.send(new WsMessage.CreateChannel("r1", ChannelType.GROUP, "g", java.util.List.of("bob")));
            String gid = alice.expect(WsMessage.ChannelCreated.class).channel().id();
            bob.expectEventually(WsMessage.ChannelCreated.class);
            alice.send(new WsMessage.Subscribe(gid, 0));
            alice.expectEventually(WsMessage.BackfillDone.class);
            bob.send(new WsMessage.Subscribe(gid, 0));
            bob.expectEventually(WsMessage.BackfillDone.class);

            // alice removes bob (any member may; no hierarchy)
            alice.send(new WsMessage.RemoveMember("rr", gid, "bob"));
            assertThat(alice.expectEvent(EventType.MEMBER_LEAVE).content()).isEqualTo("bob");
            assertThat(bob.expectEvent(EventType.MEMBER_LEAVE).content()).isEqualTo("bob"); // removed user is notified

            // a subsequent message no longer reaches the removed bob
            alice.send(new WsMessage.Op("opa", gid, OpType.POST, null, "after removal"));
            assertThat(alice.expectEvent(EventType.POST).content()).isEqualTo("after removal");
            assertThat(bob.pollWithin(500, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    /** Minimal blocking WebSocket test client over plain ws://. */
    static final class TestClient implements WebSocket.Listener, AutoCloseable {
        private final BlockingQueue<WsMessage> inbox = new LinkedBlockingQueue<>();
        private final StringBuilder buf = new StringBuilder();
        private WebSocket ws;

        static TestClient connect(int port) {
            TestClient c = new TestClient();
            c.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port), c).join();
            return c;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                inbox.add(Json.read(buf.toString(), WsMessage.class));
                buf.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        void send(WsMessage msg) {
            ws.sendText(Json.write(msg), true).join();
        }

        WsMessage pollWithin(long timeout, TimeUnit unit) {
            try {
                return inbox.poll(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        <T extends WsMessage> T expect(Class<T> type) {
            WsMessage msg = pollWithin(5, TimeUnit.SECONDS);
            assertThat(msg).as("expected %s but timed out", type.getSimpleName()).isNotNull();
            assertThat(msg).isInstanceOf(type);
            return type.cast(msg);
        }

        /** Drain messages until one of the given type appears (ignoring others). */
        <T extends WsMessage> T expectEventually(Class<T> type) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                WsMessage msg = pollWithin(500, TimeUnit.MILLISECONDS);
                if (type.isInstance(msg)) {
                    return type.cast(msg);
                }
            }
            throw new AssertionError("timed out waiting for " + type.getSimpleName());
        }

        /** Drain until an Event of the given type appears. */
        WsMessage.Event expectEvent(EventType eventType) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                WsMessage msg = pollWithin(500, TimeUnit.MILLISECONDS);
                if (msg instanceof WsMessage.Event e && e.type() == eventType) {
                    return e;
                }
            }
            throw new AssertionError("timed out waiting for " + eventType + " event");
        }

        @Override
        public void close() {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }
}
