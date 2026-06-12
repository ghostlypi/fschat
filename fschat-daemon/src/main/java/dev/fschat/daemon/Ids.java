package dev.fschat.daemon;

import java.security.SecureRandom;

/** Generates daemon-side identifiers (clientOpId / clientReqId) for idempotent ops. */
public final class Ids {

    private static final SecureRandom RNG = new SecureRandom();

    private Ids() {
    }

    public static String op() {
        return "op_" + Long.toString(RNG.nextLong() & Long.MAX_VALUE, 36);
    }

    public static String req() {
        return "req_" + Long.toString(RNG.nextLong() & Long.MAX_VALUE, 36);
    }
}
