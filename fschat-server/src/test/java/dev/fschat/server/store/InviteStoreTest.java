package dev.fschat.server.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InviteStoreTest {

    private Db db;
    private InviteStore invites;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        db = new Db(tmp.resolve("inv.db"));
        invites = new InviteStore(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void mintedCodesAreLongUniqueAndUrlSafe() {
        String a = invites.mint();
        String b = invites.mint();
        assertThat(a).isNotEqualTo(b);
        // 128 bytes base64url (no padding) -> 171 chars, only URL-safe alphabet.
        assertThat(a).hasSize(171).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void claimSucceedsExactlyOnce() {
        String code = invites.mint();
        assertThat(invites.claim(code)).isTrue();   // first use
        assertThat(invites.claim(code)).isFalse();  // single-use: second is rejected
    }

    @Test
    void claimRejectsUnknownAndBlankCodes() {
        assertThat(invites.claim("nope")).isFalse();
        assertThat(invites.claim("")).isFalse();
        assertThat(invites.claim(null)).isFalse();
    }
}
