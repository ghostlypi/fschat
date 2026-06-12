package dev.fschat.daemon.queue;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.fschat.protocol.Json;
import dev.fschat.protocol.wire.WsMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Durable, ordered queue of write ops that have not yet been acknowledged by the
 * server. Persisted to disk so ops survive a daemon restart, and keyed by
 * {@code clientOpId} so a resend after a dropped ack is idempotent (the server
 * dedupes on the same id).
 *
 * <p>The op stays in the queue from the moment it is issued until its
 * {@code Ack} arrives; on every (re)connect the whole queue is replayed in order.
 */
public final class OfflineQueue {

    private final Path file;
    private final LinkedHashMap<String, WsMessage.Op> pending = new LinkedHashMap<>();

    public OfflineQueue(Path file) {
        this.file = file;
        load();
    }

    public synchronized void add(WsMessage.Op op) {
        pending.put(op.clientOpId(), op);
        save();
    }

    public synchronized void remove(String clientOpId) {
        if (pending.remove(clientOpId) != null) {
            save();
        }
    }

    /** A snapshot of the queued ops in insertion order, for replay. */
    public synchronized List<WsMessage.Op> snapshot() {
        return new ArrayList<>(pending.values());
    }

    public synchronized boolean isEmpty() {
        return pending.isEmpty();
    }

    public synchronized int size() {
        return pending.size();
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
            // Read through the polymorphic base type so the "t" discriminator resolves.
            WsMessage[] msgs = Json.MAPPER.readValue(json, WsMessage[].class);
            for (WsMessage msg : msgs) {
                if (msg instanceof WsMessage.Op op) {
                    pending.put(op.clientOpId(), op);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load offline queue from " + file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            // Serialize with a declared base element type so each op carries its "t".
            List<WsMessage> msgs = new ArrayList<>(pending.values());
            String json = Json.MAPPER
                    .writerFor(new TypeReference<List<WsMessage>>() {
                    })
                    .writeValueAsString(msgs);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save offline queue to " + file, e);
        }
    }
}
