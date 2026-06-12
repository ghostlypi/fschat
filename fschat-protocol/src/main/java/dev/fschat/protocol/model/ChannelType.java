package dev.fschat.protocol.model;

/**
 * The kind of channel. A DM is a two-member channel; a GROUP has N members.
 *
 * <p>The model is deliberately channel-centric so that Discord-style communities
 * can be added later as a {@code COMMUNITY_CHANNEL} value (a channel with a
 * non-null {@code parentCommunityId}) without reshaping anything.
 */
public enum ChannelType {
    DM,
    GROUP,
}
