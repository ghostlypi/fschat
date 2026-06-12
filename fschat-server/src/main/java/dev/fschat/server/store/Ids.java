package dev.fschat.server.store;

import java.security.SecureRandom;

/**
 * Generates identifiers. Channels/messages use a short prefixed base36 id;
 * users use a short <b>hex</b> tag (e.g. {@code 3f9c2a}) so a user's identity
 * renders as the handle {@code name:hexid}. User-id uniqueness is enforced by
 * {@link UserStore} (which retries on the rare collision).
 */
public final class Ids {

    private static final SecureRandom RNG = new SecureRandom();

    private Ids() {
    }

    private static String gen(String prefix) {
        long n = RNG.nextLong() & Long.MAX_VALUE;
        return prefix + Long.toString(n, 36);
    }

    /** A 6-character hex user id (the {@code hexid} half of a {@code name:hexid} handle). */
    public static String user() {
        return String.format("%06x", RNG.nextInt(0x100_0000));
    }

    public static String channel() {
        return gen("ch_");
    }

    public static String message() {
        return gen("m_");
    }
}
