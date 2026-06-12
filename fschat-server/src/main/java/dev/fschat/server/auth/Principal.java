package dev.fschat.server.auth;

/** The authenticated identity carried by a verified token. */
public record Principal(String userId, String username) {
}
