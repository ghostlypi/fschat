package dev.fschat.protocol.model;

import java.util.List;

/**
 * Metadata describing a channel, as exchanged between server, daemon and the
 * local clients.
 *
 * @param id                 stable channel id (e.g. {@code ch_7af3})
 * @param type               DM or GROUP
 * @param name               group name; null for a DM (the daemon renders a DM
 *                           using the peer's username)
 * @param members            current members
 * @param parentCommunityId  always null today; reserved so a channel can later
 *                           belong to a community without a model change
 */
public record ChannelMeta(
        String id,
        ChannelType type,
        String name,
        List<UserRef> members,
        String parentCommunityId) {
}
