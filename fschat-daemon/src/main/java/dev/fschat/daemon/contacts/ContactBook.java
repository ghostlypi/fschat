package dev.fschat.daemon.contacts;

import dev.fschat.protocol.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * The local address book of contacts, persisted to {@code contacts.json} with
 * the same atomic temp-write+rename pattern as {@code OfflineQueue}. Keyed by
 * userId. A contact's effective {@link Contact#label()} drives both display and
 * local addressing.
 */
public final class ContactBook {

    private final Path file;
    private final LinkedHashMap<String, Contact> byId = new LinkedHashMap<>();

    public ContactBook(Path file) {
        this.file = file;
        load();
    }

    /** Add or update a contact (preserving an existing nickname if none is given). */
    public synchronized void upsert(String userId, String username, String nickname) {
        Contact existing = byId.get(userId);
        String nick = nickname != null ? nickname : (existing != null ? existing.nickname() : null);
        String name = username != null ? username : (existing != null ? existing.username() : userId);
        byId.put(userId, new Contact(userId, name, nick));
        save();
    }

    public synchronized void setNickname(String userId, String username, String nickname) {
        upsert(userId, username, nickname);
    }

    public synchronized void clearNickname(String userId) {
        Contact c = byId.get(userId);
        if (c != null && c.nickname() != null) {
            byId.put(userId, new Contact(c.userId(), c.username(), null));
            save();
        }
    }

    public synchronized Optional<Contact> get(String userId) {
        return Optional.ofNullable(byId.get(userId));
    }

    public synchronized boolean isContact(String userId) {
        return byId.containsKey(userId);
    }

    public synchronized Collection<Contact> all() {
        return new ArrayList<>(byId.values());
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
            Contact[] contacts = Json.MAPPER.readValue(json, Contact[].class);
            for (Contact c : contacts) {
                byId.put(c.userId(), c);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load contacts from " + file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            List<Contact> list = new ArrayList<>(byId.values());
            String json = Json.MAPPER.writeValueAsString(list);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save contacts to " + file, e);
        }
    }
}
