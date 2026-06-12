package dev.fschat.daemon.sync;

import java.util.List;

/**
 * An incremental change to a channel's transcript buffer, computed by
 * {@link ChannelView} and delivered to a viewing Vim client. Line numbers are
 * 1-based and refer to the transcript buffer (which the daemon is the authority
 * for &mdash; see {@link ChannelView}).
 */
public sealed interface ViewUpdate {

    String channelId();

    /** Append {@code lines} at the end of the transcript. */
    record Append(String channelId, List<String> lines) implements ViewUpdate {
    }

    /** Replace transcript lines {@code fromLine..toLine} (inclusive) with {@code lines}. */
    record Replace(String channelId, int fromLine, int toLine, List<String> lines) implements ViewUpdate {
    }

    /** Replace the whole transcript (after a contact/block change re-render). */
    record Reset(String channelId, List<String> lines) implements ViewUpdate {
    }

    /** Channel was renamed: full transcript + the new on-disk file path. */
    record Renamed(String channelId, String path, List<String> lines) implements ViewUpdate {
    }
}
