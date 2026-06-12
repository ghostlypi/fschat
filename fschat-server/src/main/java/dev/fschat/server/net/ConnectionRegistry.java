package dev.fschat.server.net;

import org.java_websocket.WebSocket;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks live connections per user so committed events can be fanned out to all
 * of a channel's members. A single user may have several connections (multiple
 * devices/daemons).
 */
public final class ConnectionRegistry {

    private final ConcurrentMap<String, Set<WebSocket>> byUser = new ConcurrentHashMap<>();

    public void add(String userId, WebSocket conn) {
        byUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(conn);
    }

    public void remove(String userId, WebSocket conn) {
        Set<WebSocket> set = byUser.get(userId);
        if (set != null) {
            set.remove(conn);
            if (set.isEmpty()) {
                byUser.remove(userId, set);
            }
        }
    }

    public Collection<WebSocket> forUser(String userId) {
        return byUser.getOrDefault(userId, Set.of());
    }
}
