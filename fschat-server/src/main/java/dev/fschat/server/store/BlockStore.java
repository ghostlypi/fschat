package dev.fschat.server.store;

import dev.fschat.server.util.Times;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Directional user blocks. {@code (user_id, blocked_id)} means {@code user_id}
 * has blocked {@code blocked_id} and should stop receiving their messages.
 */
public final class BlockStore {

    private final Db db;

    public BlockStore(Db db) {
        this.db = db;
    }

    public void block(String userId, String blockedId) {
        db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO blocks(user_id, blocked_id, created_ts) VALUES (?,?,?)")) {
                ps.setString(1, userId);
                ps.setString(2, blockedId);
                ps.setString(3, Times.nowIso());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void unblock(String userId, String blockedId) {
        db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM blocks WHERE user_id=? AND blocked_id=?")) {
                ps.setString(1, userId);
                ps.setString(2, blockedId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Whether {@code userId} has blocked {@code blockedId}. */
    public boolean blocks(String userId, String blockedId) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM blocks WHERE user_id=? AND blocked_id=?")) {
                ps.setString(1, userId);
                ps.setString(2, blockedId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** The set of users {@code userId} has blocked (hot path for fanout/backfill filtering). */
    public Set<String> blockedBy(String userId) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT blocked_id FROM blocks WHERE user_id=?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    Set<String> ids = new HashSet<>();
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                    return ids;
                }
            }
        });
    }
}
