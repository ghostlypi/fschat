package dev.fschat.server;

import dev.fschat.server.net.Tls;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.net.ssl.SSLContext;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * Server entry point.
 *
 * <pre>
 *   fschat-server --db ./fschat.db --https-port 8443 --ws-port 8444 \
 *                 --keystore ./dev-keystore.p12 --keystore-pass devpass
 * </pre>
 *
 * Without {@code --keystore} the server runs plaintext HTTP/WS (local dev only).
 */
@Command(name = "fschat-server", mixinStandardHelpOptions = true,
        description = "fschat server: HTTPS auth + WSS event stream over an append-only log.")
public final class Main implements Callable<Integer> {

    @Option(names = "--host", description = "Bind address (default: ${DEFAULT-VALUE}).")
    private String host = "0.0.0.0";

    @Option(names = "--https-port", description = "HTTPS auth port (default: ${DEFAULT-VALUE}).")
    private int httpsPort = 8443;

    @Option(names = "--ws-port", description = "WSS stream port (default: ${DEFAULT-VALUE}).")
    private int wsPort = 8444;

    @Option(names = "--db", description = "SQLite database file (default: ${DEFAULT-VALUE}).")
    private Path db = Path.of("fschat.db");

    @Option(names = "--keystore", description = "PKCS12 keystore for TLS. Omit for plaintext dev.")
    private Path keystore;

    @Option(names = "--keystore-pass", description = "Keystore password.")
    private String keystorePass = "";

    @Option(names = "--jwt-secret",
            description = "HMAC secret (>=32 bytes). Defaults to $FSCHAT_JWT_SECRET or a dev secret.")
    private String jwtSecret;

    @Option(names = "--token-ttl-seconds", description = "Token lifetime (default: ${DEFAULT-VALUE}).")
    private long tokenTtl = 86_400;

    @Option(names = "--admin-token",
            description = "Bearer token for POST /admin/invite. When set, registration becomes "
                    + "invite-gated. Defaults to $FSCHAT_ADMIN_TOKEN.")
    private String adminToken;

    @Override
    public Integer call() throws Exception {
        SSLContext ssl = null;
        if (keystore != null) {
            ssl = Tls.fromKeystore(keystore, keystorePass.toCharArray());
        } else {
            System.err.println("WARNING: no --keystore given; serving PLAINTEXT http/ws (dev only).");
        }

        String secret = resolveSecret();
        String admin = (adminToken != null && !adminToken.isBlank())
                ? adminToken : System.getenv("FSCHAT_ADMIN_TOKEN");

        try (FschatServer server = new FschatServer(db, host, httpsPort, wsPort, ssl, secret, tokenTtl, admin)) {
            server.start();
            String scheme = ssl != null ? "https/wss" : "http/ws";
            boolean gated = admin != null && !admin.isBlank();
            System.out.printf("fschat-server up (%s) auth=:%d stream=:%d db=%s registration=%s%n",
                    scheme, server.httpsPort(), server.wsPort(), db, gated ? "invite-only" : "open");

            CountDownLatch shutdown = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(shutdown::countDown));
            shutdown.await();
        }
        return 0;
    }

    private String resolveSecret() {
        if (jwtSecret != null && !jwtSecret.isBlank()) {
            return jwtSecret;
        }
        String env = System.getenv("FSCHAT_JWT_SECRET");
        if (env != null && !env.isBlank()) {
            return env;
        }
        System.err.println("WARNING: using a built-in dev JWT secret; set --jwt-secret in production.");
        return "fschat-dev-secret-change-me-0123456789";
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
