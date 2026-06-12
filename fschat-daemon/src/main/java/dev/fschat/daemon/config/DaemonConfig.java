package dev.fschat.daemon.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Filesystem layout for one daemon instance.
 *
 * <ul>
 *   <li>{@code configDir} (default {@code ~/.config/fschat}) holds the auth token,
 *       the local-server port file, and the offline queue.</li>
 *   <li>{@code root} (default {@code ~/fschat}) is where {@code .chat} files live.</li>
 * </ul>
 */
public final class DaemonConfig {

    private final Path configDir;
    private final Path root;

    public DaemonConfig(Path configDir, Path root) {
        this.configDir = configDir;
        this.root = root;
    }

    public Path configDir() {
        return configDir;
    }

    public Path root() {
        return root;
    }

    public Path tokenFile() {
        return configDir.resolve("token");
    }

    public Path portFile() {
        return configDir.resolve("port");
    }

    public Path queueFile() {
        return configDir.resolve("queue.json");
    }

    public Path contactsFile() {
        return configDir.resolve("contacts.json");
    }

    public Path blocksFile() {
        return configDir.resolve("blocks.json");
    }

    /**
     * Port marker dropped in the chat root so the Vim plugin can auto-discover the
     * daemon by walking up from any {@code .chat} file it opens.
     */
    public Path rootPortFile() {
        return root.resolve(".fschat-port");
    }

    public void writeToken(String token) {
        try {
            Files.createDirectories(configDir);
            Path file = tokenFile();
            Files.writeString(file, token, StandardCharsets.UTF_8);
            trySetOwnerOnly(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write token", e);
        }
    }

    public String readToken() {
        try {
            return Files.readString(tokenFile(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("no token; run `register` or `login` first", e);
        }
    }

    public int readPort() {
        try {
            return Integer.parseInt(Files.readString(portFile(), StandardCharsets.UTF_8).strip());
        } catch (IOException | NumberFormatException e) {
            throw new IllegalStateException("daemon not running (no port file); run `start` first", e);
        }
    }

    private static void trySetOwnerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem; best effort
        }
    }
}
