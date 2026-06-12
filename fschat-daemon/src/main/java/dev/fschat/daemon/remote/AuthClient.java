package dev.fschat.daemon.remote;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fschat.protocol.Json;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Talks to the server's HTTPS {@code /register} and {@code /login} endpoints. */
public final class AuthClient {

    /** Thrown when registration or login is rejected by the server. */
    public static final class AuthFailed extends RuntimeException {
        public AuthFailed(String message) {
            super(message);
        }
    }

    /** Token plus the account's name:hexid handle. */
    public record AuthInfo(String token, String handle) {
    }

    private final HttpClient http;
    private final String baseUrl;

    public AuthClient(String baseUrl, SSLContext sslContext) {
        this.baseUrl = baseUrl;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (sslContext != null) {
            b.sslContext(sslContext);
        }
        this.http = b.build();
    }

    public AuthInfo register(String username, String password, String invite) {
        return post("/register", username, password, invite);
    }

    public AuthInfo login(String identifier, String password) {
        return post("/login", identifier, password, null);
    }

    private AuthInfo post(String path, String username, String password, String invite) {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("username", username);
        fields.put("password", password);
        if (invite != null && !invite.isBlank()) {
            fields.put("invite", invite);
        }
        String body = Json.write(fields);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = Json.MAPPER.readTree(resp.body());
            if (resp.statusCode() == 200) {
                return new AuthInfo(node.path("token").asText(), node.path("handle").asText());
            }
            String error = node.path("error").asText(node.path("code").asText("request failed"));
            throw new AuthFailed("server returned " + resp.statusCode() + ": " + error);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthFailed("could not reach server at " + baseUrl + ": " + e.getMessage() + tlsHint(e));
        }
    }

    /** Detect the common scheme mismatch and suggest the fix. */
    private String tlsHint(Exception e) {
        String m = String.valueOf(e.getMessage());
        boolean plaintextToTls = m.contains("received no bytes") || m.contains("EOF reached");
        boolean tlsToPlaintext = m.contains("Unsupported or unrecognized SSL message")
                || m.contains("plaintext connection?");
        if (baseUrl.startsWith("http://") && plaintextToTls) {
            return "\n  hint: this server appears to require TLS — re-run with"
                    + " --tls --truststore <file> --truststore-pass <pw>";
        }
        if (baseUrl.startsWith("https://") && tlsToPlaintext) {
            return "\n  hint: this server is plaintext — drop --tls (and --truststore)";
        }
        return "";
    }
}
