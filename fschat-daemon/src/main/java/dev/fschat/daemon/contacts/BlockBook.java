package dev.fschat.daemon.contacts;

import dev.fschat.protocol.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Local mirror of the user's block set, persisted to {@code blocks.json}. The
 * server enforces blocking authoritatively; this client copy lets the daemon
 * hide a blocked user's already-delivered messages immediately (re-render on
 * {@code /block}) without waiting for a resync.
 */
public final class BlockBook {

    private final Path file;
    private final LinkedHashSet<String> blocked = new LinkedHashSet<>();

    public BlockBook(Path file) {
        this.file = file;
        load();
    }

    public synchronized void block(String userId) {
        if (blocked.add(userId)) {
            save();
        }
    }

    public synchronized void unblock(String userId) {
        if (blocked.remove(userId)) {
            save();
        }
    }

    public synchronized boolean isBlocked(String userId) {
        return blocked.contains(userId);
    }

    public synchronized Set<String> snapshot() {
        return new LinkedHashSet<>(blocked);
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }
            String[] ids = Json.MAPPER.readValue(json, String[].class);
            for (String id : ids) {
                blocked.add(id);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load block list from " + file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> list = new ArrayList<>(blocked);
            String json = Json.MAPPER.writeValueAsString(list);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save block list to " + file, e);
        }
    }
}
