package dev.fschat.server.auth;

import dev.fschat.server.store.Db;
import dev.fschat.server.store.InviteStore;
import dev.fschat.server.store.UserStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Registration when an InviteStore is wired (the gated/production path). */
class InviteRegistrationTest {

    private Db db;
    private InviteStore invites;
    private AuthService auth;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        db = new Db(tmp.resolve("gated.db"));
        invites = new InviteStore(db);
        auth = new AuthService(new UserStore(db), new Tokens("0123456789abcdef0123456789abcdef", 3600), invites);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void registrationRequiresAnInviteCode() {
        assertThatExceptionOfType(AuthException.class)
                .isThrownBy(() -> auth.register("alice", "password1", null))
                .matches(e -> e.code().equals(AuthException.FORBIDDEN));
        assertThatExceptionOfType(AuthException.class)
                .isThrownBy(() -> auth.register("alice", "password1", "bogus"))
                .matches(e -> e.code().equals(AuthException.FORBIDDEN));
    }

    @Test
    void validInviteRegistersAndIsSingleUse() {
        String code = invites.mint();
        AuthService.AuthResult r = auth.register("alice", "password1", code);
        assertThat(r.handle()).startsWith("alice:");
        // The same code cannot be reused.
        assertThatExceptionOfType(AuthException.class)
                .isThrownBy(() -> auth.register("bob", "password1", code))
                .matches(e -> e.code().equals(AuthException.FORBIDDEN));
    }

    @Test
    void invalidUsernameIsRejectedWithoutBurningTheInvite() {
        String code = invites.mint();
        assertThatExceptionOfType(AuthException.class)
                .isThrownBy(() -> auth.register("A!", "password1", code))
                .matches(e -> e.code().equals(AuthException.BAD_REQUEST));
        // The code survives a bad-username attempt and still works.
        assertThat(auth.register("alice", "password1", code).handle()).startsWith("alice:");
    }
}
