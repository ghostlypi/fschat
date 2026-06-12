package dev.fschat.server.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;

/** Bcrypt password hashing and verification. */
public final class Passwords {

    /** Work factor. 12 is a sensible default balance of security and latency. */
    private static final int COST = 12;

    private Passwords() {
    }

    public static String hash(String password) {
        return BCrypt.withDefaults().hashToString(COST, password.toCharArray());
    }

    public static boolean verify(String password, String hash) {
        return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
    }
}
