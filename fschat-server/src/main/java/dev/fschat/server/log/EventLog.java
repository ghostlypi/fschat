package dev.fschat.server.log;

import dev.fschat.protocol.model.EventType;
import dev.fschat.server.store.Db;
import dev.fschat.server.store.Ids;
import dev.fschat.server.util.Times;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The append-only, per-channel event log. Every write becomes an immutable row;
 * EDIT/DELETE are new rows referencing an existing {@code messageId}.
 *
 * <p>Two correctness guarantees live here:
 * <ul>
 *   <li><b>Monotonic seq:</b> each append allocates {@code MAX(seq)+1} for the
 *       channel inside a transaction. Because {@link Db} serializes all access
 *       to its single connection, allocation is race-free.</li>
 *   <li><b>Idempotency:</b> a row carries the originating {@code clientOpId};
 *       {@code (author_id, client_op_id)} is unique. Re-appending the same op
 *       (e.g. a resend after a dropped ack) returns the original event with
 *       {@code wasDuplicate=true} instead of inserting a second row.</li>
 * </ul>
 */
public final class EventLog {

    /** A stored event, enriched with the author's display name for rendering. */
    public record LogEvent(
            String channelId,
            long seq,
            EventType type,
            String messageId,
            String authorId,
            String authorName,
            String content,
            String ts,
            String clientOpId) {
    }

    /** Result of an append: the resulting event, and whether it was a no-op replay. */
    public record AppendOutcome(LogEvent event, boolean wasDuplicate) {
    }

    private final Db db;

    public EventLog(Db db) {
        this.db = db;
    }

    /**
     * Append an event, allocating its seq. For POST pass {@code messageId == null}
     * and a new one is generated; for EDIT/DELETE/MEMBER_* pass the target id.
     */
    public AppendOutcome append(
            String channelId,
            EventType type,
            String authorId,
            String messageId,
            String content,
            String clientOpId) {
        String ts = Times.nowIso();
        return db.inTx(c -> {
            Optional<LogEvent> existing = findByClientOp(c, authorId, clientOpId);
            if (existing.isPresent()) {
                return new AppendOutcome(existing.get(), true);
            }
            long seq = nextSeq(c, channelId);
            String mid = (messageId != null) ? messageId : Ids.message();
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO events(channel_id, seq, type, message_id, author_id, content, ts, client_op_id)
                    VALUES (?,?,?,?,?,?,?,?)
                    """)) {
                ps.setString(1, channelId);
                ps.setLong(2, seq);
                ps.setString(3, type.name());
                ps.setString(4, mid);
                ps.setString(5, authorId);
                ps.setString(6, content);
                ps.setString(7, ts);
                ps.setString(8, clientOpId);
                ps.executeUpdate();
            }
            String authorName = lookupUsername(c, authorId);
            LogEvent ev = new LogEvent(channelId, seq, type, mid, authorId, authorName, content, ts, clientOpId);
            return new AppendOutcome(ev, false);
        });
    }

    /** Read every event for the channel with {@code seq > fromSeq}, in seq order. */
    public List<LogEvent> readFrom(String channelId, long fromSeq) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT e.channel_id, e.seq, e.type, e.message_id, e.author_id,
                           u.username AS author_name, e.content, e.ts, e.client_op_id
                    FROM events e
                    JOIN users u ON u.id = e.author_id
                    WHERE e.channel_id = ? AND e.seq > ?
                    ORDER BY e.seq ASC
                    """)) {
                ps.setString(1, channelId);
                ps.setLong(2, fromSeq);
                try (ResultSet rs = ps.executeQuery()) {
                    List<LogEvent> events = new ArrayList<>();
                    while (rs.next()) {
                        events.add(map(rs));
                    }
                    return events;
                }
            }
        });
    }

    /** Highest seq for the channel, or 0 if empty. */
    public long headSeq(String channelId) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(MAX(seq), 0) FROM events WHERE channel_id=?")) {
                ps.setString(1, channelId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /**
     * The author of a message's original POST, used for ownership checks. Returns
     * empty if no such message exists in the channel.
     */
    public Optional<String> authorOfMessage(String channelId, String messageId) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT author_id FROM events WHERE channel_id=? AND message_id=? AND type='POST' LIMIT 1")) {
                ps.setString(1, channelId);
                ps.setString(2, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    /** Whether a message still exists and has not been deleted. */
    public boolean isLiveMessage(String channelId, String messageId) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT type FROM events WHERE channel_id=? AND message_id=? ORDER BY seq ASC")) {
                ps.setString(1, channelId);
                ps.setString(2, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean exists = false;
                    boolean deleted = false;
                    while (rs.next()) {
                        exists = true;
                        if (EventType.DELETE.name().equals(rs.getString("type"))) {
                            deleted = true;
                        }
                    }
                    return exists && !deleted;
                }
            }
        });
    }

    // ----- internals --------------------------------------------------------

    private static long nextSeq(Connection c, String channelId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM events WHERE channel_id=?")) {
            ps.setString(1, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static Optional<LogEvent> findByClientOp(Connection c, String authorId, String clientOpId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT e.channel_id, e.seq, e.type, e.message_id, e.author_id,
                       u.username AS author_name, e.content, e.ts, e.client_op_id
                FROM events e
                JOIN users u ON u.id = e.author_id
                WHERE e.author_id = ? AND e.client_op_id = ?
                """)) {
            ps.setString(1, authorId);
            ps.setString(2, clientOpId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static String lookupUsername(Connection c, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT username FROM users WHERE id=?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static LogEvent map(ResultSet rs) throws SQLException {
        return new LogEvent(
                rs.getString("channel_id"),
                rs.getLong("seq"),
                EventType.valueOf(rs.getString("type")),
                rs.getString("message_id"),
                rs.getString("author_id"),
                rs.getString("author_name"),
                rs.getString("content"),
                rs.getString("ts"),
                rs.getString("client_op_id"));
    }
}
