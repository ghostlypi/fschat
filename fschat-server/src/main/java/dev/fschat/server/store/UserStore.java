package dev.fschat.server.store;

import dev.fschat.server.util.Times;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** CRUD for users. */
public final class UserStore {

    /** A persisted user row. {@code pwdHash} is the bcrypt hash. */
    public record UserRow(String id, String username, String pwdHash, String createdTs) {
    }

    private final Db db;

    public UserStore(Db db) {
        this.db = db;
    }

    /**
     * Create a user with the given bcrypt hash. Usernames are not unique &mdash;
     * each user gets a distinct hex id, so the handle {@code name:hexid} stays unique.
     */
    public UserRow create(String username, String pwdHash) {
        String ts = Times.nowIso();
        return db.inTx(c -> {
            String id = uniqueId(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users(id, username, pwd_hash, created_ts) VALUES (?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, username);
                ps.setString(3, pwdHash);
                ps.setString(4, ts);
                ps.executeUpdate();
            }
            return new UserRow(id, username, pwdHash, ts);
        });
    }

    /** Generate a hex user id not already in use (retries on the rare collision). */
    private static String uniqueId(java.sql.Connection c) throws SQLException {
        for (int attempt = 0; attempt < 1000; attempt++) {
            String id = Ids.user();
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM users WHERE id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return id;
                    }
                }
            }
        }
        throw new StoreException("could not allocate a unique user id");
    }

    public Optional<UserRow> findByUsername(String username) {
        return db.execute(c -> findByUsername(c, username));
    }

    /** All users with this (non-unique) display name, e.g. to detect ambiguity. */
    public java.util.List<UserRow> findAllByUsername(String username) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, username, pwd_hash, created_ts FROM users WHERE username=? ORDER BY created_ts")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    java.util.List<UserRow> rows = new java.util.ArrayList<>();
                    while (rs.next()) {
                        rows.add(new UserRow(rs.getString("id"), rs.getString("username"),
                                rs.getString("pwd_hash"), rs.getString("created_ts")));
                    }
                    return rows;
                }
            }
        });
    }

    public Optional<UserRow> findById(String id) {
        return db.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, username, pwd_hash, created_ts FROM users WHERE id=?")) {
                ps.setString(1, id);
                return readOne(ps);
            }
        });
    }

    private static Optional<UserRow> findByUsername(java.sql.Connection c, String username) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, username, pwd_hash, created_ts FROM users WHERE username=?")) {
            ps.setString(1, username);
            return readOne(ps);
        }
    }

    private static Optional<UserRow> readOne(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Optional.of(new UserRow(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("pwd_hash"),
                        rs.getString("created_ts")));
            }
            return Optional.empty();
        }
    }
}
