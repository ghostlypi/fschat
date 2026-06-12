package dev.fschat.server.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.fschat.protocol.Json;
import dev.fschat.server.auth.AuthException;
import dev.fschat.server.auth.AuthService;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

    /** Request body for both endpoints. */
    public record Credentials(String username, String password) {
    }

    /** Success response: the bearer token and the account's name:hexid handle. */
    public record TokenResponse(String token, String handle) {
    }

    /** Error response. */
    public record ErrorResponse(String code, String error) {
    }

    private final HttpServer server;
    private final ExecutorService executor;

    public AuthHttpServer(InetSocketAddress addr, SSLContext sslContext, AuthService auth) {
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
                    ? auth.register(creds.username(), creds.password())
                    : auth.login(creds.username(), creds.password());
            send(ex, 200, new TokenResponse(result.token(), result.handle()));
        } catch (AuthException e) {
            int status = switch (e.code()) {
                case AuthException.INVALID_CREDENTIALS -> 401;
                default -> 400;
            };
            send(ex, status, new ErrorResponse(e.code(), e.getMessage()));
        } catch (Json.JsonException e) {
            send(ex, 400, new ErrorResponse("BAD_REQUEST", "malformed JSON body"));
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
