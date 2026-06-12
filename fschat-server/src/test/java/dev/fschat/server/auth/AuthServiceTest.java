package dev.fschat.server.auth;

import dev.fschat.server.store.Db;
import dev.fschat.server.store.StoreException;
import dev.fschat.server.store.UserStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AuthServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32 bytes

    private Db db;
    private AuthService auth;
    private Tokens tokens;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        db = new Db(tmp.resolve("auth.db"));
        tokens = new Tokens(SECRET, 3600);
        auth = new AuthService(new UserStore(db), tokens);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void registerThenVerifyYieldsIdentity() {
        String token = auth.register("alice", "password1").token();
        Principal p = auth.verify(token);
        assertThat(p.username()).isEqualTo("alice");
        assertThat(p.userId()).matches("[0-9a-f]+");
    }

    @Test
    void loginAfterRegisterReturnsUsableToken() {
        auth.register("alice", "password1");
        String token = auth.login("alice", "password1").token();
        assertThat(auth.verify(token).username()).isEqualTo("alice");
    }

    @Test
    void loginWithWrongPasswordIsRejected() {
        auth.register("alice", "password1");
        assertThatExceptionOfType(AuthException.class)
                .isThrownBy(() -> auth.login("alice", "wrong"))
                .matches(e -> e.code().equals(AuthException.INVALID_CREDENTIALS));
    }

    @Test
    void loginUnknownUserIsRejected() {
        assertThatExceptionOfType(AuthException.class)
                .isThrownBy(() -> auth.login("ghost", "password1"))
                .matches(e -> e.code().equals(AuthException.INVALID_CREDENTIALS));
    }

    @Test
    void duplicateUsernamesAreAllowedWithDistinctHandles() {
        AuthService.AuthResult a = auth.register("alice", "password1");
        AuthService.AuthResult b = auth.register("alice", "password2");
        assertThat(a.userId()).isNotEqualTo(b.userId());
        assertThat(a.handle()).startsWith("alice:");
        assertThat(b.handle()).startsWith("alice:");
        // Each logs in by its own handle and resolves to the right account.
        assertThat(auth.verify(auth.login(a.handle(), "password1").token()).userId()).isEqualTo(a.userId());
        assertThat(auth.verify(auth.login(b.handle(), "password2").token()).userId()).isEqualTo(b.userId());
    }

    @Test
    void ambiguousBareUsernameLoginIsRejected() {
        auth.register("alice", "password1");
        auth.register("alice", "password2");
        assertThatExceptionOfType(AuthException.class)
                .isThrownBy(() -> auth.login("alice", "password1"))
                .matches(e -> e.code().equals(AuthException.BAD_REQUEST));
    }

    @Test
    void invalidUsernameOrPasswordIsRejected() {
        assertThatExceptionOfType(AuthException.class)
                .isThrownBy(() -> auth.register("A!", "password1"))
                .matches(e -> e.code().equals(AuthException.BAD_REQUEST));
        assertThatExceptionOfType(AuthException.class)
                .isThrownBy(() -> auth.register("alice", "short"))
                .matches(e -> e.code().equals(AuthException.BAD_REQUEST));
    }

    @Test
    void expiredTokenIsRejected() {
        Tokens shortLived = new Tokens(SECRET, -10); // already expired
        String token = shortLived.issue("usr_x", "alice");
        assertThatExceptionOfType(AuthException.class)
                .isThrownBy(() -> tokens.verify(token))
                .matches(e -> e.code().equals(AuthException.INVALID_TOKEN));
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        String token = new Tokens("ffffffffffffffffffffffffffffffff", 3600).issue("usr_x", "alice");
        assertThatExceptionOfType(AuthException.class)
                .isThrownBy(() -> tokens.verify(token))
                .matches(e -> e.code().equals(AuthException.INVALID_TOKEN));
    }
}
