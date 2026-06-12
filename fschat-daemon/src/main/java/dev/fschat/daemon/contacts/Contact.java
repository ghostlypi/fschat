package dev.fschat.daemon.contacts;

/**
 * A local contact: a user you've added or nicknamed. Purely client-side (never
 * sent on the wire). The {@code nickname} is optional; {@link #label()} is how
 * this user is displayed and addressed locally.
 *
 * @param userId   the user's stable hex id
 * @param username last-known display name (for the default label)
 * @param nickname optional local nickname (overrides the username)
 */
public record Contact(String userId, String username, String nickname) {

    public String label() {
        return (nickname != null && !nickname.isBlank()) ? nickname : username;
    }
}
