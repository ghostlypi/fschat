package dev.fschat.server.net;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-connection state, attached to each WebSocket. A connection is anonymous
 * until {@link #authenticate} succeeds; only then is a userId bound and ops are
 * accepted. {@code subscribed} is the set of channels whose live events this
 * connection wants pushed.
 */
public final class Session {

    private volatile String userId;
    private volatile String username;
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();

    public boolean isAuthenticated() {
        return userId != null;
    }

    public void authenticate(String userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    public String userId() {
        return userId;
    }

    public String username() {
        return username;
    }

    public void subscribe(String channelId) {
        subscribed.add(channelId);
    }

    public boolean isSubscribed(String channelId) {
        return subscribed.contains(channelId);
    }
}
