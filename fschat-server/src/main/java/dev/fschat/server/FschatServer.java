package dev.fschat.server;

import dev.fschat.server.auth.AuthService;
import dev.fschat.server.auth.Tokens;
import dev.fschat.server.channel.ChannelService;
import dev.fschat.server.log.EventLog;
import dev.fschat.server.net.AuthHttpServer;
import dev.fschat.server.net.FschatWsServer;
import dev.fschat.server.store.BlockStore;
import dev.fschat.server.store.ChannelStore;
import dev.fschat.server.store.Db;
import dev.fschat.server.store.InviteStore;
import dev.fschat.server.store.UserStore;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Wires the persistence, auth, channel and network layers into a runnable
 * server. Serves account bootstrap over HTTPS and the live stream over WSS,
 * sharing one {@link SSLContext} (null = plain HTTP/WS for local dev/tests).
 */
public final class FschatServer implements AutoCloseable {

    private final Db db;
    private final AuthHttpServer authServer;
    private final FschatWsServer wsServer;

    public FschatServer(Path dbFile, String host, int httpsPort, int wsPort,
                        SSLContext sslContext, String jwtSecret, long tokenTtlSeconds) {
        this(dbFile, host, httpsPort, wsPort, sslContext, jwtSecret, tokenTtlSeconds, null);
    }

    /**
     * @param adminToken if non-blank, enables {@code POST /admin/invite} (guarded by this
     *                   bearer token) and makes registration require an invite code
     */
    public FschatServer(Path dbFile, String host, int httpsPort, int wsPort,
                        SSLContext sslContext, String jwtSecret, long tokenTtlSeconds,
                        String adminToken) {
        this.db = new Db(dbFile);
        UserStore users = new UserStore(db);
        ChannelStore channels = new ChannelStore(db);
        EventLog log = new EventLog(db);
        // Setting an admin token turns the invite system on: registration becomes
        // invite-gated AND POST /admin/invite is enabled to mint codes. With no token,
        // registration stays open (local/dev) and there's no admin surface.
        boolean gated = adminToken != null && !adminToken.isBlank();
        InviteStore invites = new InviteStore(db);
        AuthService auth = new AuthService(
                users, new Tokens(jwtSecret, tokenTtlSeconds), gated ? invites : null);
        ChannelService channelService = new ChannelService(users, channels, log, new BlockStore(db));

        this.authServer = new AuthHttpServer(
                new InetSocketAddress(host, httpsPort), sslContext, auth, invites, adminToken);
        this.wsServer = new FschatWsServer(new InetSocketAddress(host, wsPort), sslContext, auth, channelService);
    }

    public void start() throws InterruptedException {
        authServer.start();
        wsServer.start();
        wsServer.awaitStart(10, TimeUnit.SECONDS);
    }

    public int httpsPort() {
        return authServer.port();
    }

    public int wsPort() {
        return wsServer.getPort();
    }

    @Override
    public void close() {
        try {
            wsServer.stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        authServer.stop();
        db.close();
    }
}
