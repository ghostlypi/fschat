package dev.fschat.protocol.wire;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.fschat.protocol.model.ChannelMeta;
import dev.fschat.protocol.model.ChannelType;
import dev.fschat.protocol.model.EventType;
import dev.fschat.protocol.model.OpType;

import java.util.List;

/**
 * Every message exchanged between the daemon and the server over the WebSocket
 * connection. Exactly one JSON object per WebSocket text frame; the concrete
 * type is carried in a {@code "t"} discriminator field that Jackson manages.
 *
 * <p>Direction conventions (C = client/daemon, S = server):
 * <pre>
 *   C-&gt;S : Auth, Subscribe, Op, CreateChannel, AddMember, Ping
 *   S-&gt;C : AuthOk, Event, Ack, BackfillDone, ChannelCreated, Error, Pong
 * </pre>
 *
 * <p>The permitted subtypes are the nested records below, so the {@code permits}
 * clause is inferred (all in one compilation unit).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "t")
@JsonSubTypes({
        @JsonSubTypes.Type(value = WsMessage.Auth.class, name = "auth"),
        @JsonSubTypes.Type(value = WsMessage.AuthOk.class, name = "auth_ok"),
        @JsonSubTypes.Type(value = WsMessage.Subscribe.class, name = "subscribe"),
        @JsonSubTypes.Type(value = WsMessage.BackfillDone.class, name = "backfill_done"),
        @JsonSubTypes.Type(value = WsMessage.Op.class, name = "op"),
        @JsonSubTypes.Type(value = WsMessage.Ack.class, name = "ack"),
        @JsonSubTypes.Type(value = WsMessage.Event.class, name = "event"),
        @JsonSubTypes.Type(value = WsMessage.CreateChannel.class, name = "create_channel"),
        @JsonSubTypes.Type(value = WsMessage.ChannelCreated.class, name = "channel_created"),
        @JsonSubTypes.Type(value = WsMessage.AddMember.class, name = "add_member"),
        @JsonSubTypes.Type(value = WsMessage.RemoveMember.class, name = "remove_member"),
        @JsonSubTypes.Type(value = WsMessage.Rename.class, name = "rename"),
        @JsonSubTypes.Type(value = WsMessage.Block.class, name = "block"),
        @JsonSubTypes.Type(value = WsMessage.Unblock.class, name = "unblock"),
        @JsonSubTypes.Type(value = WsMessage.BlockAck.class, name = "block_ack"),
        @JsonSubTypes.Type(value = WsMessage.Error.class, name = "error"),
        @JsonSubTypes.Type(value = WsMessage.Ping.class, name = "ping"),
        @JsonSubTypes.Type(value = WsMessage.Pong.class, name = "pong"),
})
public sealed interface WsMessage {

    // ----- C->S: authentication --------------------------------------------

    /** First frame after connect. The JWT obtained from /login. */
    record Auth(String token) implements WsMessage {
    }

    /**
     * S-&gt;C: authentication accepted. Tells the daemon who it is and every
     * channel it currently belongs to (so it can create local files and
     * subscribe). The daemon supplies its own last-seen seq when subscribing.
     */
    record AuthOk(String userId, String username, List<ChannelMeta> channels) implements WsMessage {
    }

    // ----- resumable sync ---------------------------------------------------

    /** C-&gt;S: stream every event for {@code channelId} with seq &gt; {@code fromSeq}. */
    record Subscribe(String channelId, long fromSeq) implements WsMessage {
    }

    /** S-&gt;C: backfill for {@code channelId} is complete; {@code headSeq} is current head. */
    record BackfillDone(String channelId, long headSeq) implements WsMessage {
    }

    // ----- writes -----------------------------------------------------------

    /**
     * C-&gt;S: a write operation.
     *
     * @param clientOpId caller-generated id, unique per user action; used for
     *                   idempotent acks across reconnect
     * @param messageId  null for POST (server assigns); required for EDIT/DELETE
     * @param content    null for DELETE
     */
    record Op(String clientOpId, String channelId, OpType op, String messageId, String content)
            implements WsMessage {
    }

    /** S-&gt;C: a committed write, echoing the clientOpId with the assigned id + seq. */
    record Ack(String clientOpId, String channelId, String messageId, long seq) implements WsMessage {
    }

    /**
     * S-&gt;C: a committed log event, pushed to all subscribers (including the
     * author &mdash; the server is the single source of truth, so clients render
     * what the server stored rather than echoing optimistically).
     *
     * @param authorName display name of the author, included for convenient rendering
     * @param content    message text; null for DELETE
     * @param ts         ISO-8601 UTC timestamp string
     */
    record Event(
            String channelId,
            long seq,
            EventType type,
            String messageId,
            String authorId,
            String authorName,
            String content,
            String ts) implements WsMessage {
    }

    // ----- channel management ----------------------------------------------

    /**
     * C-&gt;S: create a DM or group.
     *
     * @param clientReqId     caller-generated correlation id
     * @param name            group name; null/ignored for a DM
     * @param memberUsernames other members to include (the requester is added
     *                        implicitly)
     */
    record CreateChannel(String clientReqId, ChannelType type, String name, List<String> memberUsernames)
            implements WsMessage {
    }

    /** S-&gt;C: a channel was created (or already existed); also pushed to other members. */
    record ChannelCreated(String clientReqId, ChannelMeta channel) implements WsMessage {
    }

    /** C-&gt;S: add a user to a group. The server appends a MEMBER_ADD event. */
    record AddMember(String clientReqId, String channelId, String username) implements WsMessage {
    }

    /**
     * C-&gt;S: remove a user from a group (any member may; {@code username} = self means leave).
     * The server appends a MEMBER_LEAVE event.
     */
    record RemoveMember(String clientReqId, String channelId, String username) implements WsMessage {
    }

    /** C-&gt;S: rename a group (members only; rejected for DMs). Appends a RENAME event. */
    record Rename(String clientReqId, String channelId, String name) implements WsMessage {
    }

    // ----- blocking ---------------------------------------------------------

    /**
     * C-&gt;S: block a user (directional). {@code targetUserId} is a bare hexid &mdash;
     * the daemon resolves any nickname/label to an id before sending.
     */
    record Block(String clientReqId, String targetUserId) implements WsMessage {
    }

    /** C-&gt;S: remove a block. */
    record Unblock(String clientReqId, String targetUserId) implements WsMessage {
    }

    /** S-&gt;C: acknowledges a block/unblock (blocking produces no channel event). */
    record BlockAck(String clientReqId, String targetUserId, boolean blocked) implements WsMessage {
    }

    // ----- misc -------------------------------------------------------------

    /**
     * S-&gt;C: a typed error.
     *
     * @param code  machine-readable code, e.g. {@code NOT_OWNER}, {@code NOT_MEMBER}
     * @param refId the clientOpId or clientReqId this error refers to, if any
     */
    record Error(String code, String refId, String msg) implements WsMessage {
    }

    /** C-&gt;S application-level keepalive. */
    record Ping(long nonce) implements WsMessage {
    }

    /** S-&gt;C keepalive reply. */
    record Pong(long nonce) implements WsMessage {
    }
}
