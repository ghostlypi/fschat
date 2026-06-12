package dev.fschat.server.store;

import dev.fschat.protocol.model.ChannelMeta;
import dev.fschat.protocol.model.ChannelType;
import dev.fschat.protocol.model.UserRef;
import dev.fschat.server.util.Times;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** CRUD for channels and their membership. */
public final class ChannelStore {

    private final Db db;

    public ChannelStore(Db db) {
        this.db = db;
    }

    /** Create a channel with the given members (which must include the creator). */
    public ChannelMeta create(ChannelType type, String name, List<String> memberIds) {
        String id = Ids.channel();
        String ts = Times.nowIso();
        return db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO channels(id, type, name, parent_community_id, created_ts) VALUES (?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, type.name());
                ps.setString(3, name);
                ps.setString(4, null);
                ps.setString(5, ts);
                ps.executeUpdate();
            }
            for (String userId : memberIds) {
                insertMember(c, id, userId);
            }
            return loadMeta(c, id).orElseThrow();
        });
    }

    /** Find an existing DM channel whose membership is exactly {@code {a, b}}. */
    public Optional<ChannelMeta> findDm(String userA, String userB) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT c.id FROM channels c
                    WHERE c.type = 'DM'
                      AND (SELECT COUNT(*) FROM members m WHERE m.channel_id = c.id) = 2
                      AND EXISTS (SELECT 1 FROM members m WHERE m.channel_id = c.id AND m.user_id = ?)
                      AND EXISTS (SELECT 1 FROM members m WHERE m.channel_id = c.id AND m.user_id = ?)
                    """)) {
                ps.setString(1, userA);
                ps.setString(2, userB);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return loadMeta(c, rs.getString("id"));
                    }
                    return Optional.<ChannelMeta>empty();
                }
            }
        });
    }

    public void addMember(String channelId, String userId) {
        db.inTx(c -> {
            insertMember(c, channelId, userId);
            return null;
        });
    }

    public void removeMember(String channelId, String userId) {
        db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM members WHERE channel_id=? AND user_id=?")) {
                ps.setString(1, channelId);
                ps.setString(2, userId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public boolean isMember(String channelId, String userId) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM members WHERE channel_id=? AND user_id=?")) {
                ps.setString(1, channelId);
                ps.setString(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public void rename(String channelId, String name) {
        db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE channels SET name=? WHERE id=?")) {
                ps.setString(1, name);
                ps.setString(2, channelId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Member ids for a channel (used for broadcast fanout). */
    public List<String> memberIds(String channelId) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT user_id FROM members WHERE channel_id=?")) {
                ps.setString(1, channelId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> ids = new ArrayList<>();
                    while (rs.next()) {
                        ids.add(rs.getString("user_id"));
                    }
                    return ids;
                }
            }
        });
    }

    public Optional<ChannelMeta> get(String channelId) {
        return db.execute(c -> loadMeta(c, channelId));
    }

    /** All channels the user is a member of. */
    public List<ChannelMeta> channelsForUser(String userId) {
        return db.execute(c -> {
            List<String> channelIds = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT channel_id FROM members WHERE user_id=?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        channelIds.add(rs.getString("channel_id"));
                    }
                }
            }
            List<ChannelMeta> result = new ArrayList<>();
            for (String id : channelIds) {
                loadMeta(c, id).ifPresent(result::add);
            }
            return result;
        });
    }

    // ----- internals (run inside a caller-supplied connection) --------------

    private static void insertMember(Connection c, String channelId, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO members(channel_id, user_id, role) VALUES (?,?, 'MEMBER')")) {
            ps.setString(1, channelId);
            ps.setString(2, userId);
            ps.executeUpdate();
        }
    }

    private static Optional<ChannelMeta> loadMeta(Connection c, String channelId) throws SQLException {
        ChannelType type;
        String name;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT type, name FROM channels WHERE id=?")) {
            ps.setString(1, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                type = ChannelType.valueOf(rs.getString("type"));
                name = rs.getString("name");
            }
        }
        List<UserRef> members = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT u.id, u.username FROM members m
                JOIN users u ON u.id = m.user_id
                WHERE m.channel_id = ?
                ORDER BY u.username
                """)) {
            ps.setString(1, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    members.add(new UserRef(rs.getString("id"), rs.getString("username")));
                }
            }
        }
        return Optional.of(new ChannelMeta(channelId, type, name, members, null));
    }
}
