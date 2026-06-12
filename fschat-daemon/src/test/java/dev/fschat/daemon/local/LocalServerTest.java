package dev.fschat.daemon.local;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fschat.daemon.queue.OfflineQueue;
import dev.fschat.daemon.remote.WsClient;
import dev.fschat.daemon.sync.SyncEngine;
import dev.fschat.protocol.Json;
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

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class LocalServerTest {

    @TempDir
    Path tmp;
    private Db serverDb;
    private FschatWsServer server;
    private WsClient client;
    private LocalServer local;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        serverDb = new Db(tmp.resolve("server.db"));
        UserStore users = new UserStore(serverDb);
        AuthService auth = new AuthService(users, new Tokens("0123456789abcdef0123456789abcdef", 3600));
        ChannelService channels = new ChannelService(users, new ChannelStore(serverDb), new EventLog(serverDb), new dev.fschat.server.store.BlockStore(serverDb));
        server = new FschatWsServer(new InetSocketAddress("127.0.0.1", 0), null, auth, channels);
        server.start();
        server.awaitStart(5, TimeUnit.SECONDS);
        token = auth.register("alice", "password1").token();
        auth.register("bob", "password1");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (local != null) {
            local.close();
        }
        if (client != null) {
            client.close();
        }
        server.stop(1000);
        serverDb.close();
    }

    private static Path firstChat(Path dir) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".chat")).findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static void await(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMs + "ms");
            }
            Thread.sleep(25);
        }
    }

    @Test
    void vimOpensSendsAndReceivesLivePushIncludingEdit() throws Exception {
        Path root = tmp.resolve("fschat-alice");
        SyncEngine engine = new SyncEngine(root, new dev.fschat.daemon.directory.Directory(
                new dev.fschat.daemon.contacts.ContactBook(tmp.resolve("contacts.json")),
                new dev.fschat.daemon.contacts.BlockBook(tmp.resolve("blocks.json"))));
        client = new WsClient(URI.create("ws://127.0.0.1:" + server.getPort()), token, null,
                new OfflineQueue(tmp.resolve("q.json")), engine);
        engine.bind(client);
        local = new LocalServer(engine, tmp.resolve("port"));
        engine.setSink(local);
        local.start();
        client.start();

        await(() -> engine.meId() != null, 5_000);

        engine.createDm("bob");
        Path dmsDir = root.resolve("dms");
        await(() -> firstChat(dmsDir) != null, 5_000);
        Path dmFile = firstChat(dmsDir);
        String channelId = engine.channelIdForPath(dmFile).orElseThrow();

        try (FakeVim vim = FakeVim.connect(local.port())) {
            // open
            JsonNode open = vim.request(Map.of("c", "open", "path", dmFile.toString()));
            assertThat(open.path("ok").asBoolean()).isTrue();
            assertThat(open.path("channelId").asText()).isEqualTo(channelId);
            assertThat(jsonLines(open)).contains("#fschat v1");

            // send -> message round-trips and arrives as an append push
            JsonNode sendReply = vim.request(Map.of("c", "send", "channelId", channelId, "content", "hi from vim"));
            assertThat(sendReply.path("ok").asBoolean()).isTrue();

            JsonNode push = vim.pushes.poll(5, TimeUnit.SECONDS);
            assertThat(push).isNotNull();
            assertThat(push.path("push").asText()).isEqualTo("append");
            assertThat(jsonLines(push)).anyMatch(l -> l.equals("hi from vim"));

            String messageId = messageIdFrom(push);

            // edit my own message -> arrives as a replace push
            vim.request(Map.of("c", "edit", "channelId", channelId, "messageId", messageId, "content", "edited!"));
            JsonNode replace = vim.pushes.poll(5, TimeUnit.SECONDS);
            assertThat(replace).isNotNull();
            assertThat(replace.path("push").asText()).isEqualTo("replace");
            assertThat(jsonLines(replace)).anyMatch(l -> l.equals("edited!"));
        }
    }

    @Test
    void composeSlashCommandsRunAsCommands() throws Exception {
        Path root = tmp.resolve("fschat-alice");
        SyncEngine engine = new SyncEngine(root, new dev.fschat.daemon.directory.Directory(
                new dev.fschat.daemon.contacts.ContactBook(tmp.resolve("contacts.json")),
                new dev.fschat.daemon.contacts.BlockBook(tmp.resolve("blocks.json"))));
        client = new WsClient(URI.create("ws://127.0.0.1:" + server.getPort()), token, null,
                new OfflineQueue(tmp.resolve("q.json")), engine);
        engine.bind(client);
        local = new LocalServer(engine, null);
        engine.setSink(local);
        local.start();
        client.start();
        await(() -> engine.meId() != null, 5_000);

        engine.createDm("bob");
        Path dmsDir = root.resolve("dms");
        await(() -> firstChat(dmsDir) != null, 5_000);
        String channelId = engine.channelIdForPath(firstChat(dmsDir)).orElseThrow();

        try (FakeVim vim = FakeVim.connect(local.port())) {
            vim.request(Map.of("c", "open", "path", firstChat(dmsDir).toString()));

            // /help is a command: reply carries info, nothing is posted
            JsonNode help = vim.request(Map.of("c", "send", "channelId", channelId, "content", "/help"));
            assertThat(help.path("ok").asBoolean()).isTrue();
            assertThat(help.path("info").asText()).contains("/block");

            // "//hi" escapes to a literal post of "/hi"
            vim.request(Map.of("c", "send", "channelId", channelId, "content", "//hi"));
            JsonNode append = vim.pushes.poll(5, TimeUnit.SECONDS);
            assertThat(append.path("push").asText()).isEqualTo("append");
            assertThat(jsonLines(append)).anyMatch(l -> l.equals("/hi"));

            // /block (DM peer) acks with feedback and triggers a full re-render (reset)
            JsonNode block = vim.request(Map.of("c", "send", "channelId", channelId, "content", "/block"));
            assertThat(block.path("ok").asBoolean()).isTrue();
            assertThat(block.path("info").asText()).contains("blocked bob");
            JsonNode reset = vim.pushes.poll(5, TimeUnit.SECONDS);
            assertThat(reset.path("push").asText()).isEqualTo("reset");

            // unknown command is reported as an error, not posted
            JsonNode bad = vim.request(Map.of("c", "send", "channelId", channelId, "content", "/wat"));
            assertThat(bad.path("ok").asBoolean()).isFalse();
            assertThat(bad.path("error").asText()).contains("unknown command");
        }
    }

    @Test
    void createWaitsForServerConfirmationAndFailsLoudlyOnRejection() throws Exception {
        Path root = tmp.resolve("fschat-alice");
        SyncEngine engine = new SyncEngine(root, new dev.fschat.daemon.directory.Directory(
                new dev.fschat.daemon.contacts.ContactBook(tmp.resolve("contacts.json")),
                new dev.fschat.daemon.contacts.BlockBook(tmp.resolve("blocks.json"))));
        client = new WsClient(URI.create("ws://127.0.0.1:" + server.getPort()), token, null,
                new OfflineQueue(tmp.resolve("q.json")), engine);
        engine.bind(client);
        local = new LocalServer(engine, null);
        engine.setSink(local);
        local.start();
        client.start();
        await(() -> engine.meId() != null, 5_000);

        try (FakeVim vim = FakeVim.connect(local.port())) {
            // success: the reply waits for the server's ChannelCreated and reports the file path
            JsonNode ok = vim.request(Map.of("c", "create", "members", java.util.List.of("bob")));
            assertThat(ok.path("ok").asBoolean()).isTrue();
            assertThat(ok.path("info").asText()).contains("created").contains("dms");

            // rejection: an unknown user is reported as a failure (fails loudly), not silently accepted
            JsonNode bad = vim.request(Map.of("c", "create", "members", java.util.List.of("ghost")));
            assertThat(bad.path("ok").asBoolean()).isFalse();
            assertThat(bad.path("error").asText()).contains("USER_NOT_FOUND");
        }
    }

    @Test
    void openUnknownPathReportsNotReady() throws Exception {
        Path root = tmp.resolve("fschat-alice");
        SyncEngine engine = new SyncEngine(root, new dev.fschat.daemon.directory.Directory(
                new dev.fschat.daemon.contacts.ContactBook(tmp.resolve("contacts.json")),
                new dev.fschat.daemon.contacts.BlockBook(tmp.resolve("blocks.json"))));
        client = new WsClient(URI.create("ws://127.0.0.1:" + server.getPort()), token, null,
                new OfflineQueue(tmp.resolve("q.json")), engine);
        engine.bind(client);
        local = new LocalServer(engine, null);
        engine.setSink(local);
        local.start();
        client.start();
        await(() -> engine.meId() != null, 5_000);

        try (FakeVim vim = FakeVim.connect(local.port())) {
            JsonNode reply = vim.request(Map.of("c", "open", "path", root.resolve("dms/ghost.chat").toString()));
            assertThat(reply.path("ok").asBoolean()).isFalse();
            assertThat(reply.path("error").asText()).contains("not ready");
        }
    }

    private static java.util.List<String> jsonLines(JsonNode node) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        node.path("lines").forEach(n -> lines.add(n.asText()));
        return lines;
    }

    private static String messageIdFrom(JsonNode appendPush) {
        for (JsonNode lineNode : appendPush.path("lines")) {
            String line = lineNode.asText();
            if (line.startsWith("#msg ")) {
                for (String token : line.split(" ")) {
                    if (token.startsWith("id=")) {
                        return token.substring(3);
                    }
                }
            }
        }
        throw new AssertionError("no #msg id in push: " + appendPush);
    }

    /** A fake Vim JSON-channel client over plain TCP. */
    static final class FakeVim implements AutoCloseable {
        final BlockingQueue<JsonNode> replies = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> pushes = new LinkedBlockingQueue<>();
        private final Socket socket;
        private final OutputStream out;
        private int nextId = 1;

        private FakeVim(Socket socket) throws Exception {
            this.socket = socket;
            this.out = socket.getOutputStream();
            Thread reader = new Thread(this::readLoop, "fake-vim-reader");
            reader.setDaemon(true);
            reader.start();
        }

        static FakeVim connect(int port) throws Exception {
            return new FakeVim(new Socket("127.0.0.1", port));
        }

        private void readLoop() {
            try (com.fasterxml.jackson.core.JsonParser parser =
                         Json.MAPPER.getFactory().createParser(socket.getInputStream())) {
                while (parser.nextToken() != null) {
                    JsonNode frame = Json.MAPPER.readTree(parser);
                    if (frame == null || !frame.isArray()) {
                        continue;
                    }
                    int id = frame.get(0).asInt();
                    JsonNode body = frame.get(1);
                    (id == 0 ? pushes : replies).add(body);
                }
            } catch (Exception ignored) {
                // socket closed
            }
        }

        JsonNode request(Map<String, Object> body) throws Exception {
            int id = nextId++;
            String json = Json.write(new Object[]{id, new LinkedHashMap<>(body)});
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
            JsonNode reply = replies.poll(5, TimeUnit.SECONDS);
            if (reply == null) {
                throw new AssertionError("no reply for request " + body);
            }
            return reply;
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }
}
