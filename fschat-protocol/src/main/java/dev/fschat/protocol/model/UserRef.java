package dev.fschat.protocol.model;

/**
 * A lightweight reference to a user: a stable id plus a display username.
 */
public record UserRef(String id, String username) {
}
