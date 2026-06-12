package dev.fschat.server.net;

import dev.fschat.protocol.Json;
import dev.fschat.server.auth.AuthService;
import dev.fschat.server.auth.Tokens;
import dev.fschat.server.net.AuthHttpServer.ErrorResponse;
import dev.fschat.server.net.AuthHttpServer.TokenResponse;
import dev.fschat.server.store.Db;
import dev.fschat.server.store.UserStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AuthHttpServerTest {

    private Db db;
    private AuthHttpServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        db = new Db(tmp.resolve("auth.db"));
        AuthService auth = new AuthService(new UserStore(db),
                new Tokens("0123456789abcdef0123456789abcdef", 3600));
        // Plain HTTP for the test (sslContext == null).
        server = new AuthHttpServer(new InetSocketAddress("127.0.0.1", 0), null, auth);
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() {
        server.stop();
        db.close();
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void registerThenLoginOverHttp() throws Exception {
        HttpResponse<String> reg = post("/register", "{\"username\":\"alice\",\"password\":\"password1\"}");
        assertThat(reg.statusCode()).isEqualTo(200);
        assertThat(Json.read(reg.body(), TokenResponse.class).token()).isNotBlank();

        HttpResponse<String> login = post("/login", "{\"username\":\"alice\",\"password\":\"password1\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(Json.read(login.body(), TokenResponse.class).token()).isNotBlank();
    }

    @Test
    void duplicateUsernameRegistersDistinctAccounts() throws Exception {
        HttpResponse<String> r1 = post("/register", "{\"username\":\"bob\",\"password\":\"password1\"}");
        HttpResponse<String> r2 = post("/register", "{\"username\":\"bob\",\"password\":\"password1\"}");
        assertThat(r1.statusCode()).isEqualTo(200);
        assertThat(r2.statusCode()).isEqualTo(200);
        String h1 = Json.read(r1.body(), TokenResponse.class).handle();
        String h2 = Json.read(r2.body(), TokenResponse.class).handle();
        assertThat(h1).startsWith("bob:");
        assertThat(h2).startsWith("bob:");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void badLoginReturns401() throws Exception {
        post("/register", "{\"username\":\"carol\",\"password\":\"password1\"}");
        HttpResponse<String> bad = post("/login", "{\"username\":\"carol\",\"password\":\"nope\"}");
        assertThat(bad.statusCode()).isEqualTo(401);
        assertThat(Json.read(bad.body(), ErrorResponse.class).code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void shortPasswordReturns400() throws Exception {
        HttpResponse<String> bad = post("/register", "{\"username\":\"dave\",\"password\":\"x\"}");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(Json.read(bad.body(), ErrorResponse.class).code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void getMethodReturns405() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/login")).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(405);
    }
}
