package dev.fschat.protocol.model;

/**
 * The type of an entry in a channel's append-only event log.
 *
 * <p>{@link #POST}, {@link #EDIT} and {@link #DELETE} concern messages;
 * {@link #MEMBER_ADD}, {@link #MEMBER_LEAVE} and {@link #RENAME} are channel
 * events that sync and render as system lines through the same machinery.
 */
public enum EventType {
    POST,
    EDIT,
    DELETE,
    MEMBER_ADD,
    MEMBER_LEAVE,
    /** Channel renamed; the new name is carried in the event content. */
    RENAME,
}
