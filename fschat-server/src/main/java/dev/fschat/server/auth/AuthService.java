package dev.fschat.server.auth;

import dev.fschat.server.store.UserStore;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Registration and login. Produces JWTs on success; the same tokens authenticate
 * the WebSocket connection later.
 *
 * <p>Usernames are not unique, so login accepts either a {@code name:hexid} handle
 * or a bare username (the latter only when it is unambiguous).
 */
public final class AuthService {

    /** Outcome of register/login: the token plus the resolved identity. */
    public record AuthResult(String token, String userId, String username) {
        public String handle() {
            return username + ":" + userId;
        }
    }

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_]{2,32}");
    private static final int MIN_PASSWORD = 8;

    private final UserStore users;
    private final Tokens tokens;

    public AuthService(UserStore users, Tokens tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /** Create a new account (duplicate usernames allowed) and return a token + its handle. */
    public AuthResult register(String username, String password) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new AuthException(AuthException.BAD_REQUEST,
                    "username must be 2-32 chars of [a-z0-9_]");
        }
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new AuthException(AuthException.BAD_REQUEST,
                    "password must be at least " + MIN_PASSWORD + " characters");
        }
        UserStore.UserRow row = users.create(username, Passwords.hash(password));
        return result(row);
    }

    /**
     * Verify credentials and return a token + handle. {@code identifier} is a
     * {@code name:hexid} handle, or a bare username if it is unambiguous.
     */
    public AuthResult login(String identifier, String password) {
        UserStore.UserRow row = resolveForLogin(identifier);
        if (password == null || !Passwords.verify(password, row.pwdHash())) {
            throw new AuthException(AuthException.INVALID_CREDENTIALS, "invalid credentials");
        }
        return result(row);
    }

    /** Verify a bearer token (e.g. the first WebSocket frame). */
    public Principal verify(String token) {
        return tokens.verify(token);
    }

    private UserStore.UserRow resolveForLogin(String identifier) {
        if (identifier == null) {
            throw new AuthException(AuthException.INVALID_CREDENTIALS, "invalid credentials");
        }
        int colon = identifier.indexOf(':');
        if (colon >= 0) {
            return users.findById(identifier.substring(colon + 1))
                    .orElseThrow(() -> new AuthException(AuthException.INVALID_CREDENTIALS, "invalid credentials"));
        }
        List<UserStore.UserRow> matches = users.findAllByUsername(identifier);
        if (matches.isEmpty()) {
            throw new AuthException(AuthException.INVALID_CREDENTIALS, "invalid credentials");
        }
        if (matches.size() > 1) {
            throw new AuthException(AuthException.BAD_REQUEST,
                    "ambiguous username; log in with name:hexid");
        }
        return matches.get(0);
    }

    private AuthResult result(UserStore.UserRow row) {
        return new AuthResult(tokens.issue(row.id(), row.username()), row.id(), row.username());
    }
}
