package dev.fschat.protocol.model;

/**
 * A client-initiated write operation on a channel. The server validates each
 * op against the authenticated user before turning it into a log event.
 */
public enum OpType {
    /** Add a new message. The server assigns the messageId. */
    POST,
    /** Replace the content of one of your own messages. */
    EDIT,
    /** Delete one of your own messages. */
    DELETE,
}
