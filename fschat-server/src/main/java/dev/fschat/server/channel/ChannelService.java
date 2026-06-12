package dev.fschat.server.channel;

import dev.fschat.protocol.model.ChannelMeta;
import dev.fschat.protocol.model.ChannelType;
import dev.fschat.protocol.model.EventType;
import dev.fschat.protocol.model.OpType;
import dev.fschat.protocol.wire.WsMessage;
import dev.fschat.server.auth.Principal;
import dev.fschat.server.log.EventLog;
import dev.fschat.server.log.EventLog.AppendOutcome;
import dev.fschat.server.store.BlockStore;
import dev.fschat.server.store.ChannelStore;
import dev.fschat.server.store.Ids;
import dev.fschat.server.store.UserStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * High-level channel operations with authorization. This is the single seam
 * where "may this user do this?" is decided; today it is membership + ownership,
 * and it is where role/permission checks would later be added for communities.
 *
 * <p>Critically, every write derives the author from the authenticated
 * {@link Principal}, never from anything the client supplied.
 */
public final class ChannelService {

    /** Maximum message length, enforced authoritatively to bound memory/DoS. */
    public static final int MAX_CONTENT_CHARS = 8192;

    /** Result of adding a member: the updated channel plus the MEMBER_ADD event to fan out. */
    public record MemberAddResult(ChannelMeta channel, EventLog.LogEvent event) {
    }

    /** Result of removing a member: the updated channel, the MEMBER_LEAVE event, and who was removed. */
    public record MemberRemoveResult(ChannelMeta channel, EventLog.LogEvent event, String removedUserId) {
    }

    private final UserStore users;
    private final ChannelStore channels;
    private final EventLog log;
    private final BlockStore blocks;

    public ChannelService(UserStore users, ChannelStore channels, EventLog log, BlockStore blocks) {
        this.users = users;
        this.channels = channels;
        this.log = log;
        this.blocks = blocks;
    }

    public List<ChannelMeta> channelsForUser(String userId) {
        return channels.channelsForUser(userId);
    }

    public List<String> memberIds(String channelId) {
        return channels.memberIds(channelId);
    }

    public Optional<ChannelMeta> getChannel(String channelId) {
        return channels.get(channelId);
    }

    /**
     * Backfill source: events with seq &gt; fromSeq, only if the user is a member.
     * Blocking only affects DMs: a blocked author's message events are filtered out
     * of a DM backfill, but never out of a group (to avoid a group, leave it).
     */
    public List<EventLog.LogEvent> backfill(Principal who, String channelId, long fromSeq) {
        requireMember(who.userId(), channelId);
        List<EventLog.LogEvent> events = log.readFrom(channelId, fromSeq);
        if (!isDirectMessage(channelId)) {
            return events;
        }
        Set<String> blocked = blocks.blockedBy(who.userId());
        if (blocked.isEmpty()) {
            return events;
        }
        List<EventLog.LogEvent> visible = new ArrayList<>(events.size());
        for (EventLog.LogEvent e : events) {
            if (isMessageEvent(e.type()) && blocked.contains(e.authorId())) {
                continue;
            }
            visible.add(e);
        }
        return visible;
    }

    /** Whether {@code recipientId} has blocked {@code authorId} (used by DM fanout). */
    public boolean hasBlocked(String recipientId, String authorId) {
        return blocks.blocks(recipientId, authorId);
    }

    public boolean isDirectMessage(String channelId) {
        return channels.get(channelId).map(m -> m.type() == ChannelType.DM).orElse(false);
    }

    /** Only message events are subject to blocking; membership/rename system events always show. */
    public static boolean isMessageEvent(EventType type) {
        return type == EventType.POST || type == EventType.EDIT || type == EventType.DELETE;
    }

    public long headSeq(String channelId) {
        return log.headSeq(channelId);
    }

    /**
     * Create a DM or group. The creator is always a member. A DM is deduped on
     * the member pair so repeated {@code fschat dm bob} returns the same channel.
     */
    public ChannelMeta createChannel(Principal creator, ChannelType type, String name, List<String> memberUsernames) {
        String creatorId = creator.userId();
        Set<String> memberIds = new LinkedHashSet<>();
        memberIds.add(creatorId);
        // Remember which token resolved to each id, so a masked rejection echoes the same token
        // a genuine "no such user" would — making the two indistinguishable.
        java.util.Map<String, String> tokenById = new java.util.HashMap<>();
        for (String token : (memberUsernames == null ? List.<String>of() : memberUsernames)) {
            UserStore.UserRow row = resolveMember(token);
            memberIds.add(row.id());
            tokenById.putIfAbsent(row.id(), token);
        }

        if (type == ChannelType.DM) {
            if (memberIds.size() != 2) {
                throw new ChannelException(ChannelException.BAD_REQUEST,
                        "a DM must have exactly two distinct members");
            }
            String peerId = memberIds.stream().filter(id -> !id.equals(creatorId)).findFirst().orElseThrow();
            if (blocks.blocks(creatorId, peerId)) {
                throw new ChannelException(ChannelException.BLOCKED, "you have blocked this user; /unblock to message them");
            }
            requireNotBlockedBy(creatorId, peerId, tokenById.getOrDefault(peerId, "user"));
            Optional<ChannelMeta> existing = channels.findDm(creatorId, peerId);
            if (existing.isPresent()) {
                return existing.get();
            }
            return channels.create(ChannelType.DM, null, new ArrayList<>(memberIds));
        }

        if (name == null || name.isBlank()) {
            throw new ChannelException(ChannelException.BAD_REQUEST, "a group requires a name");
        }
        for (String memberId : memberIds) {
            if (!memberId.equals(creatorId)) {
                requireNotBlockedBy(creatorId, memberId, tokenById.getOrDefault(memberId, "user"));
            }
        }
        return channels.create(ChannelType.GROUP, name, new ArrayList<>(memberIds));
    }

    /** Add a user to a group (any member may; rejected for DMs) and record a MEMBER_ADD event. */
    public MemberAddResult addMember(Principal actor, String channelId, String username, String clientReqId) {
        requireMember(actor.userId(), channelId);
        requireGroup(channelId);
        UserStore.UserRow target = resolveMember(username);
        requireNotBlockedBy(actor.userId(), target.id(), username);
        if (!channels.isMember(channelId, target.id())) {
            channels.addMember(channelId, target.id());
        }
        AppendOutcome outcome = log.append(channelId, EventType.MEMBER_ADD, actor.userId(),
                Ids.message(), target.username(), clientReqId);
        ChannelMeta meta = channels.get(channelId)
                .orElseThrow(() -> new ChannelException(ChannelException.NOT_FOUND, "channel vanished"));
        return new MemberAddResult(meta, outcome.event());
    }

    /**
     * Remove a user from a group (any member may; rejected for DMs). Removing yourself is leaving.
     * Records a MEMBER_LEAVE event whose content is the removed user's name.
     */
    public MemberRemoveResult removeMember(Principal actor, String channelId, String username, String clientReqId) {
        requireMember(actor.userId(), channelId);
        requireGroup(channelId);
        UserStore.UserRow target = resolveMember(username);
        // Append the event first (records who removed whom), then drop the membership.
        AppendOutcome outcome = log.append(channelId, EventType.MEMBER_LEAVE, actor.userId(),
                Ids.message(), target.username(), clientReqId);
        if (channels.isMember(channelId, target.id())) {
            channels.removeMember(channelId, target.id());
        }
        ChannelMeta meta = channels.get(channelId)
                .orElseThrow(() -> new ChannelException(ChannelException.NOT_FOUND, "channel vanished"));
        return new MemberRemoveResult(meta, outcome.event(), target.id());
    }

    private void requireGroup(String channelId) {
        if (isDirectMessage(channelId)) {
            throw new ChannelException(ChannelException.BAD_REQUEST, "not a group");
        }
    }

    /**
     * You cannot contact {@code otherId} if they have blocked you. Masked as a plain
     * "no such user" (using the token the caller typed) so a block is indistinguishable
     * from a non-existent account &mdash; the block is never revealed to the blocked user.
     */
    private void requireNotBlockedBy(String actorId, String otherId, String token) {
        if (blocks.blocks(otherId, actorId)) {
            throw new ChannelException(ChannelException.USER_NOT_FOUND, "no such user: " + token);
        }
    }

    /** Rename a group (members only; rejected for DMs). Returns the RENAME event to fan out. */
    public EventLog.LogEvent rename(Principal actor, String channelId, String name, String clientReqId) {
        requireMember(actor.userId(), channelId);
        ChannelMeta meta = channels.get(channelId)
                .orElseThrow(() -> new ChannelException(ChannelException.NOT_FOUND, "no such channel"));
        if (meta.type() == ChannelType.DM) {
            throw new ChannelException(ChannelException.BAD_REQUEST, "cannot rename a DM");
        }
        if (name == null || name.isBlank()) {
            throw new ChannelException(ChannelException.BAD_REQUEST, "a name is required");
        }
        String trimmed = name.strip();
        channels.rename(channelId, trimmed);
        return log.append(channelId, EventType.RENAME, actor.userId(), Ids.message(), trimmed, clientReqId).event();
    }

    /** Block a user (directional): the actor stops receiving the target's messages. */
    public void block(Principal actor, String targetId) {
        if (targetId == null || targetId.equals(actor.userId())) {
            throw new ChannelException(ChannelException.BAD_REQUEST, "invalid block target");
        }
        if (users.findById(targetId).isEmpty()) {
            throw new ChannelException(ChannelException.USER_NOT_FOUND, "no such user: " + targetId);
        }
        blocks.block(actor.userId(), targetId);
    }

    public void unblock(Principal actor, String targetId) {
        blocks.unblock(actor.userId(), targetId);
    }

    /**
     * Validate and apply a write op, returning the committed event (or the
     * original on an idempotent replay).
     */
    public AppendOutcome applyOp(Principal who, WsMessage.Op op) {
        requireMember(who.userId(), op.channelId());
        return switch (op.op()) {
            case POST -> {
                requireContent(op.content());
                yield log.append(op.channelId(), EventType.POST, who.userId(), null, op.content(), op.clientOpId());
            }
            case EDIT -> {
                requireOwnedLiveMessage(who, op.channelId(), op.messageId(), OpType.EDIT);
                requireContent(op.content());
                yield log.append(op.channelId(), EventType.EDIT, who.userId(), op.messageId(),
                        op.content(), op.clientOpId());
            }
            case DELETE -> {
                requireOwnedLiveMessage(who, op.channelId(), op.messageId(), OpType.DELETE);
                yield log.append(op.channelId(), EventType.DELETE, who.userId(), op.messageId(),
                        null, op.clientOpId());
            }
        };
    }

    /**
     * Resolve a {@code name:hexid} handle or a bare username to a user. Since
     * usernames are not unique, a bare username that matches more than one user
     * is rejected as ambiguous &mdash; address them with {@code name:hexid}.
     */
    private UserStore.UserRow resolveMember(String token) {
        String t = token.trim();
        int colon = t.indexOf(':');
        if (colon >= 0) {
            return users.findById(t.substring(colon + 1)) // address by the hexid half of the handle
                    .orElseThrow(() -> new ChannelException(ChannelException.USER_NOT_FOUND, "no such user: " + token));
        }
        List<UserStore.UserRow> matches = users.findAllByUsername(t);
        if (matches.isEmpty()) {
            throw new ChannelException(ChannelException.USER_NOT_FOUND, "no such user: " + token);
        }
        if (matches.size() > 1) {
            throw new ChannelException(ChannelException.BAD_REQUEST,
                    "ambiguous username '" + t + "'; use name:hexid");
        }
        return matches.get(0);
    }

    private void requireContent(String content) {
        if (content == null || content.isEmpty()) {
            throw new ChannelException(ChannelException.BAD_REQUEST, "empty message");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new ChannelException(ChannelException.BAD_REQUEST,
                    "message exceeds " + MAX_CONTENT_CHARS + " characters");
        }
    }

    private void requireOwnedLiveMessage(Principal who, String channelId, String messageId, OpType op) {
        if (messageId == null) {
            throw new ChannelException(ChannelException.BAD_REQUEST, op + " requires a messageId");
        }
        String author = log.authorOfMessage(channelId, messageId)
                .orElseThrow(() -> new ChannelException(ChannelException.NOT_FOUND, "no such message: " + messageId));
        if (!author.equals(who.userId())) {
            throw new ChannelException(ChannelException.NOT_OWNER, "you do not own message " + messageId);
        }
        if (!log.isLiveMessage(channelId, messageId)) {
            throw new ChannelException(ChannelException.NOT_FOUND, "message already deleted: " + messageId);
        }
    }

    private void requireMember(String userId, String channelId) {
        if (!channels.isMember(channelId, userId)) {
            throw new ChannelException(ChannelException.NOT_MEMBER, "not a member of " + channelId);
        }
    }
}
