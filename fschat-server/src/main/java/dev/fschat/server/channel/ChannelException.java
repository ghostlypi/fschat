package dev.fschat.server.channel;

/** A channel-operation failure carrying a machine-readable code for the wire. */
public final class ChannelException extends RuntimeException {

    public static final String NOT_MEMBER = "NOT_MEMBER";
    public static final String NOT_OWNER = "NOT_OWNER";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String BLOCKED = "BLOCKED";

    private final String code;

    public ChannelException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
