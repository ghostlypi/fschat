package dev.fschat.daemon.sync;

import dev.fschat.daemon.queue.OfflineQueue;
import dev.fschat.daemon.remote.WsClient;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class SyncEngineTest {

    @TempDir
    Path tmp;
    private Db serverDb;
    private FschatWsServer server;
    private WsClient client;

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

    private String token;

    @AfterEach
    void tearDown() throws Exception {
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
    void createDmPostMessageWritesFileAndEmitsUpdate() throws Exception {
        Path root = tmp.resolve("fschat-alice");
        BlockingQueue<ViewUpdate> updates = new LinkedBlockingQueue<>();
        var directory = new dev.fschat.daemon.directory.Directory(
                new dev.fschat.daemon.contacts.ContactBook(tmp.resolve("contacts.json")),
                new dev.fschat.daemon.contacts.BlockBook(tmp.resolve("blocks.json")));
        SyncEngine engine = new SyncEngine(root, directory, updates::add);
        client = new WsClient(URI.create("ws://127.0.0.1:" + server.getPort()), token, null,
                new OfflineQueue(tmp.resolve("q.json")), engine);
        engine.bind(client);
        client.start();

        await(() -> engine.meId() != null, 5_000);

        // Create a DM; the daemon materializes ~/fschat-alice/dms/bob-<hexid>.chat
        engine.createDm("bob");
        Path dmsDir = root.resolve("dms");
        await(() -> firstChat(dmsDir) != null, 5_000);
        Path dmFile = firstChat(dmsDir);
        assertThat(dmFile.getFileName().toString()).startsWith("bob-");

        String channelId = engine.channelIdForPath(dmFile).orElseThrow();

        // Empty channel file: header + compose marker, no messages.
        String initial = Files.readString(dmFile);
        assertThat(initial).contains("#fschat v1").contains("#=== compose");
        assertThat(initial).doesNotContain("#msg");

        // Post a message; it round-trips from the server and lands in the file + as an update.
        engine.post(channelId, "hello bob");
        await(() -> {
            try {
                return Files.readString(dmFile).contains("hello bob");
            } catch (Exception e) {
                return false;
            }
        }, 5_000);

        ViewUpdate.Append append = null;
        ViewUpdate u;
        while ((u = updates.poll(2, TimeUnit.SECONDS)) != null) {
            if (u instanceof ViewUpdate.Append a && a.lines().contains("hello bob")) {
                append = a;
                break;
            }
        }
        assertThat(append).as("append update for the posted message").isNotNull();

        // Transcript snapshot (what `open` returns) reflects the message.
        assertThat(engine.transcript(channelId).orElseThrow())
                .anyMatch(line -> line.equals("hello bob"))
                .anyMatch(line -> line.startsWith("#msg ") && line.contains("author=alice:"));
    }
}
