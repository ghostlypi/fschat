package dev.fschat.daemon.file;

import dev.fschat.protocol.model.ChannelMeta;
import dev.fschat.protocol.model.ChannelType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Maps channels to {@code .chat} files under the user's fschat root and writes
 * them atomically. DMs live under {@code dms/<peer>.chat}; groups under
 * {@code groups/<name>.chat}. These files exist for {@code ls}/{@code cat} and
 * offline reading; while a buffer is open, the daemon drives live updates.
 */
public final class FileStore {

    private final Path root;
    private final String meId;

    public FileStore(Path root, String meId) {
        this.root = root;
        this.meId = meId;
    }

    public Path pathFor(ChannelMeta meta) {
        if (meta.type() == ChannelType.DM) {
            // Include the peer's hexid: usernames are not unique, so the name
            // alone could collide across different peers.
            String stem = meta.members().stream()
                    .filter(m -> !m.id().equals(meId))
                    .map(m -> sanitize(m.username()) + "-" + m.id())
                    .findFirst()
                    .orElse("unknown");
            return root.resolve("dms").resolve(stem + ".chat");
        }
        String name = meta.name() != null ? meta.name() : meta.id();
        return root.resolve("groups").resolve(sanitize(name) + ".chat");
    }

    public void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write chat file " + path, e);
        }
    }

    /** Make a filesystem-safe file stem from a username or group name. */
    static String sanitize(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        cleaned = cleaned.replaceAll("(^-+)|(-+$)", "");
        return cleaned.isEmpty() ? "channel" : cleaned;
    }
}
