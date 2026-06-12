package dev.fschat.server.util;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** ISO-8601 UTC timestamp helpers. Timestamps travel the wire and the file as strings. */
public final class Times {

    private Times() {
    }

    /** The current instant as an ISO-8601 string, e.g. {@code 2026-06-11T17:00:00.123Z}. */
    public static String nowIso() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }
}
