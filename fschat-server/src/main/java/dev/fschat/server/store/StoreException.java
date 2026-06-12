package dev.fschat.server.store;

/** Unchecked wrapper for persistence failures. */
public class StoreException extends RuntimeException {
    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public StoreException(String message) {
        super(message);
    }
}
