package dev.fschat.server.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.fschat.protocol.Json;
import dev.fschat.server.auth.AuthException;
import dev.fschat.server.auth.AuthService;
import dev.fschat.server.store.InviteStore;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal HTTPS surface for account bootstrap: {@code POST /register} and
 * {@code POST /login}, both taking and returning JSON. Stream traffic goes over
 * the separate WebSocket server.
 *
 * <p>Pass {@code sslContext == null} to bind plain HTTP (used by tests); pass a
 * context to serve HTTPS.
 */
public final class AuthHttpServer {

    /** Request body for both endpoints ({@code invite} ignored by /login). */
    public record Credentials(String username, String password, String invite) {
    }

    /** Success response: the bearer token and the account's name:hexid handle. */
    public record TokenResponse(String token, String handle) {
    }

    /** Admin mint response. */
    public record InviteResponse(String code) {
    }

    /** Error response. */
    public record ErrorResponse(String code, String error) {
    }

    private final HttpServer server;
    private final ExecutorService executor;

    /** Open-registration / no-admin overload (tests, local dev). */
    public AuthHttpServer(InetSocketAddress addr, SSLContext sslContext, AuthService auth) {
        this(addr, sslContext, auth, null, null);
    }

    /**
     * @param invites    backs the admin mint endpoint (null disables it)
     * @param adminToken bearer token guarding {@code POST /admin/invite}; if null
     *                   or blank the admin endpoint is not registered at all
     */
    public AuthHttpServer(InetSocketAddress addr, SSLContext sslContext, AuthService auth,
                          InviteStore invites, String adminToken) {
        try {
            if (sslContext == null) {
                this.server = HttpServer.create(addr, 0);
            } else {
                HttpsServer https = HttpsServer.create(addr, 0);
                https.setHttpsConfigurator(new HttpsConfigurator(sslContext));
                this.server = https;
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to bind auth server on " + addr, e);
        }
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "fschat-auth-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/register", ex -> handle(ex, auth, true));
        server.createContext("/login", ex -> handle(ex, auth, false));
        if (invites != null && adminToken != null && !adminToken.isBlank()) {
            server.createContext("/admin/invite", ex -> handleAdminInvite(ex, invites, adminToken));
        }
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private static void handle(HttpExchange ex, AuthService auth, boolean register) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                send(ex, 405, new ErrorResponse("METHOD_NOT_ALLOWED", "use POST"));
                return;
            }
            byte[] body = ex.getRequestBody().readAllBytes();
            Credentials creds = Json.read(new String(body, StandardCharsets.UTF_8), Credentials.class);
            AuthService.AuthResult result = register
                    ? auth.register(creds.username(), creds.password(), creds.invite())
                    : auth.login(creds.username(), creds.password());
            send(ex, 200, new TokenResponse(result.token(), result.handle()));
        } catch (AuthException e) {
            int status = switch (e.code()) {
                case AuthException.INVALID_CREDENTIALS -> 401;
                case AuthException.FORBIDDEN -> 403;
                default -> 400;
            };
            send(ex, status, new ErrorResponse(e.code(), e.getMessage()));
        } catch (Json.JsonException e) {
            send(ex, 400, new ErrorResponse("BAD_REQUEST", "malformed JSON body"));
        } catch (RuntimeException e) {
            send(ex, 500, new ErrorResponse("INTERNAL", "internal error"));
        }
    }

    /** {@code POST /admin/invite} with {@code Authorization: Bearer <adminToken>} -> a fresh code. */
    private static void handleAdminInvite(HttpExchange ex, InviteStore invites, String adminToken)
            throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                send(ex, 405, new ErrorResponse("METHOD_NOT_ALLOWED", "use POST"));
                return;
            }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String presented = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : "";
            boolean ok = MessageDigest.isEqual(
                    presented.getBytes(StandardCharsets.UTF_8),
                    adminToken.getBytes(StandardCharsets.UTF_8));
            if (!ok) {
                send(ex, 401, new ErrorResponse("UNAUTHORIZED", "bad or missing admin token"));
                return;
            }
            send(ex, 200, new InviteResponse(invites.mint()));
        } catch (RuntimeException e) {
            send(ex, 500, new ErrorResponse("INTERNAL", "internal error"));
        }
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
