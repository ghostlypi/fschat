package dev.fschat.server.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the single SQLite {@link Connection} and serializes all access to it.
 *
 * <p>A JDBC {@code Connection} is not thread-safe, and SQLite serializes writes
 * regardless, so we guard every operation with the monitor of this object. That
 * also makes per-channel sequence allocation in {@link EventLog} atomic without
 * any extra locking. Throughput is more than adequate for the MVP's scale.
 */
public final class Db implements AutoCloseable {

    private final Connection conn;

    public Db(Path file) {
        try {
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA foreign_keys=ON");
            }
            bootstrap();
        } catch (SQLException e) {
            throw new StoreException("failed to open database at " + file, e);
        }
    }

    private void bootstrap() {
        String script = stripSqlComments(readResource("/schema.sql"));
        execute(c -> {
            try (Statement st = c.createStatement()) {
                for (String raw : script.split(";")) {
                    String stmt = raw.strip();
                    if (!stmt.isEmpty()) {
                        st.execute(stmt);
                    }
                }
            }
            return null;
        });
    }

    /**
     * Remove {@code --} line comments so that splitting on {@code ;} cannot
     * produce a comment-only fragment (which SQLite rejects as incomplete
     * input). Safe here because no schema string literal contains {@code --}.
     */
    private static String stripSqlComments(String script) {
        StringBuilder sb = new StringBuilder(script.length());
        for (String line : script.split("\n", -1)) {
            int comment = line.indexOf("--");
            sb.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return sb.toString();
    }

    /** Run read-only or single-statement work under the connection lock (autocommit). */
    public synchronized <T> T execute(SqlFn<T> work) {
        try {
            return work.apply(conn);
        } catch (SQLException e) {
            throw new StoreException("database operation failed", e);
        }
    }

    /** Run {@code work} in a transaction; commit on success, roll back on any failure. */
    public synchronized <T> T inTx(SqlFn<T> work) {
        try {
            conn.setAutoCommit(false);
            try {
                T result = work.apply(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                safeRollback();
                if (e instanceof StoreException se) {
                    throw se;
                }
                throw new StoreException("transaction failed", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StoreException("transaction management failed", e);
        }
    }

    private void safeRollback() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    private static String readResource(String path) {
        try (InputStream in = Db.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new StoreException("resource not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StoreException("failed to read resource " + path, e);
        }
    }

    /** A unit of JDBC work that may throw {@link SQLException}. */
    @FunctionalInterface
    public interface SqlFn<T> {
        T apply(Connection c) throws SQLException;
    }
}
