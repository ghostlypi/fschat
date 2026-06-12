package dev.fschat.server.store;

import dev.fschat.server.util.Times;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.util.Base64;

/**
 * Single-use registration invite codes. A code is 128 bytes of secure random,
 * base64url-encoded. Registration claims a code atomically (single-use); the
 * {@code used_by} column records who consumed it, for auditing.
 */
public final class InviteStore {

    /** Code entropy in bytes. */
    public static final int CODE_BYTES = 128;

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final Db db;

    public InviteStore(Db db) {
        this.db = db;
    }

    /** Generate, persist, and return a fresh single-use invite code. */
    public String mint() {
        byte[] raw = new byte[CODE_BYTES];
        RNG.nextBytes(raw);
        String code = B64.encodeToString(raw);
        String ts = Times.nowIso();
        db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO invites(code, created_ts) VALUES (?,?)")) {
                ps.setString(1, code);
                ps.setString(2, ts);
                ps.executeUpdate();
            }
            return null;
        });
        return code;
    }

    /**
     * Atomically claim (burn) a code. Returns {@code true} iff the code exists and
     * was unused; {@code false} for unknown, blank, or already-claimed codes. The
     * single SQLite connection serializes this, so the check-and-set is race-free.
     */
    public boolean claim(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String ts = Times.nowIso();
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE invites SET used_ts=? WHERE code=? AND used_ts IS NULL")) {
                ps.setString(1, ts);
                ps.setString(2, code);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Record (best-effort) which user consumed a claimed code. */
    public void recordUser(String code, String userId) {
        db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE invites SET used_by=? WHERE code=?")) {
                ps.setString(1, userId);
                ps.setString(2, code);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
