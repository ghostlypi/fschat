package dev.fschat.daemon;

import dev.fschat.daemon.config.DaemonConfig;
import dev.fschat.daemon.contacts.BlockBook;
import dev.fschat.daemon.contacts.ContactBook;
import dev.fschat.daemon.directory.Directory;
import dev.fschat.daemon.local.LocalServer;
import dev.fschat.daemon.queue.OfflineQueue;
import dev.fschat.daemon.remote.WsClient;
import dev.fschat.daemon.sync.SyncEngine;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wires the daemon for {@code start}: the {@link SyncEngine}, the {@link WsClient}
 * to the server, and the {@link LocalServer} that Vim connects to. The local
 * server is also the engine's update sink, so server events flow straight to the
 * editor.
 */
public final class DaemonApp implements AutoCloseable {

    private final WsClient client;
    private final LocalServer local;
    private final Path rootPortFile;

    public DaemonApp(DaemonConfig config, URI serverWsUri, SSLContext sslContext) {
        this.rootPortFile = config.rootPortFile();
        String token = config.readToken();
        OfflineQueue queue = new OfflineQueue(config.queueFile());
        Directory directory = new Directory(
                new ContactBook(config.contactsFile()), new BlockBook(config.blocksFile()));
        SyncEngine engine = new SyncEngine(config.root(), directory);
        this.client = new WsClient(serverWsUri, token, sslContext, queue, engine);
        engine.bind(client);
        this.local = new LocalServer(engine, config.portFile());
        engine.setSink(local);
    }

    public void start() {
        local.start();
        client.start();
        writeRootPortMarker();
    }

    public int localPort() {
        return local.port();
    }

    /** Drop the port marker in the chat root so `vim <root>/.../x.chat` finds this daemon. */
    private void writeRootPortMarker() {
        try {
            Files.createDirectories(rootPortFile.getParent());
            Files.writeString(rootPortFile, Integer.toString(local.port()), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // non-fatal: the plugin can still be pointed at the port explicitly
        }
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(rootPortFile);
        } catch (IOException ignored) {
            // best effort
        }
        local.close();
        client.close();
    }
}
