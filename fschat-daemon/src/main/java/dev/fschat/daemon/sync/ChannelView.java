package dev.fschat.daemon.sync;

import dev.fschat.daemon.directory.Directory;
import dev.fschat.protocol.model.ChannelMeta;
import dev.fschat.protocol.model.ChannelType;
import dev.fschat.protocol.model.UserRef;
import dev.fschat.protocol.wire.WsMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The in-memory projection of one channel's event log into a renderable
 * transcript. The daemon is the single authority on the transcript's line
 * layout: a Vim client loads the full transcript on {@code open} and thereafter
 * applies the incremental {@link ViewUpdate}s this class computes.
 *
 * <p>How a user is displayed (bare name / nickname / {@code name:hexid}) and
 * whether they are hidden (blocked) is resolved through the daemon-wide
 * {@link Directory}. Rendering, line-range computation, and incremental updates
 * all operate over the SAME {@link #visibleBlocks()} list, so blocked authors
 * never desync line numbers.
 *
 * <p>Events apply strictly in seq order; duplicates drop and gaps buffer.
 * Not thread-safe; {@link SyncEngine} confines each view to serial use.
 */
public final class ChannelView {

    /** Header lines in the transcript buffer (file adds #synced-seq + compose marker). */
    private static final int HEADER_LINES = 3;

    private sealed interface Block {
        long seq();

        List<String> render(Directory directory);
    }

    private static final class MsgBlock implements Block {
        final String messageId;
        final String authorId;
        final String authorName;
        final String ts;
        final long seq;
        String content;
        boolean edited;
        boolean deleted;

        MsgBlock(String messageId, String authorId, String authorName, String ts, long seq, String content) {
            this.messageId = messageId;
            this.authorId = authorId;
            this.authorName = authorName;
            this.ts = ts;
            this.seq = seq;
            this.content = content;
        }

        @Override
        public long seq() {
            return seq;
        }

        @Override
        public List<String> render(Directory directory) {
            StringBuilder header = new StringBuilder("#msg id=").append(messageId)
                    .append(" author=").append(directory.displayAuthor(authorId, authorName))
                    .append(" seq=").append(seq)
                    .append(" ts=").append(ts);
            if (edited) {
                header.append(" edited=true");
            }
            if (deleted) {
                header.append(" deleted=true");
            }
            List<String> lines = new ArrayList<>();
            lines.add(header.toString());
            if (deleted) {
                lines.add("[deleted]");
            } else {
                for (String b : content.split("\n", -1)) {
                    lines.add(b);
                }
            }
            return lines;
        }
    }

    private static final class SysBlock implements Block {
        final long seq;
        final String ts;
        final String text;

        SysBlock(long seq, String ts, String text) {
            this.seq = seq;
            this.ts = ts;
            this.text = text;
        }

        @Override
        public long seq() {
            return seq;
        }

        @Override
        public List<String> render(Directory directory) {
            return List.of("#sys seq=" + seq + " ts=" + ts, "— " + text);
        }
    }

    private final String channelId;
    private final Directory directory;
    private ChannelType type;
    private String name;
    private List<UserRef> members;

    private final List<Block> blocks = new ArrayList<>();
    private final Map<String, Integer> indexByMessageId = new java.util.HashMap<>();
    private final TreeMap<Long, WsMessage.Event> pending = new TreeMap<>();
    private long syncedSeq = 0;

    public ChannelView(ChannelMeta meta, Directory directory) {
        this.channelId = meta.id();
        this.directory = directory;
        updateMeta(meta);
    }

    public void updateMeta(ChannelMeta meta) {
        this.type = meta.type();
        this.name = meta.name();
        this.members = meta.members();
    }

    public String channelId() {
        return channelId;
    }

    public ChannelType type() {
        return type;
    }

    public String name() {
        return name;
    }

    public List<UserRef> members() {
        return members;
    }

    public ChannelMeta meta() {
        return new ChannelMeta(channelId, type, name, members, null);
    }

    public long syncedSeq() {
        return syncedSeq;
    }

    /**
     * Apply an event, returning the resulting transcript updates. Usually 0 (a
     * duplicate, a buffered gap, a blocked author, or a RENAME handled by a full
     * re-render) or 1; possibly several when a buffered gap flushes at once.
     */
    public List<ViewUpdate> applyEvent(WsMessage.Event e) {
        List<ViewUpdate> out = new ArrayList<>();
        if (e.seq() <= syncedSeq) {
            return out; // duplicate
        }
        pending.put(e.seq(), e);
        while (pending.containsKey(syncedSeq + 1)) {
            WsMessage.Event next = pending.remove(syncedSeq + 1);
            ViewUpdate u = applyInOrder(next);
            syncedSeq = next.seq();
            if (u != null) {
                out.add(u);
            }
        }
        return out;
    }

    private ViewUpdate applyInOrder(WsMessage.Event e) {
        return switch (e.type()) {
            case POST -> {
                MsgBlock block = new MsgBlock(e.messageId(), e.authorId(), e.authorName(), e.ts(), e.seq(), e.content());
                indexByMessageId.put(e.messageId(), blocks.size());
                blocks.add(block);
                yield isHiddenAuthor(block.authorId)
                        ? null                                   // hidden: applied to model, no UI update
                        : new ViewUpdate.Append(channelId, withLeadingBlank(block.render(directory)));
            }
            case EDIT -> mutateMessage(e.messageId(), b -> {
                b.content = e.content();
                b.edited = true;
            });
            case DELETE -> mutateMessage(e.messageId(), b -> {
                b.deleted = true;
                b.content = null;
            });
            case MEMBER_ADD -> appendSystem(e.seq(), e.ts(), e.authorName() + " added " + e.content());
            case MEMBER_LEAVE -> appendSystem(e.seq(), e.ts(),
                    (e.content() != null && !e.content().equals(e.authorName()))
                            ? e.authorName() + " removed " + e.content()
                            : e.authorName() + " left");
            case RENAME -> {
                this.name = e.content();
                blocks.add(new SysBlock(e.seq(), e.ts(), "renamed to " + e.content()));
                yield null;                                      // SyncEngine issues a full Renamed re-render
            }
        };
    }

    private ViewUpdate appendSystem(long seq, String ts, String text) {
        SysBlock block = new SysBlock(seq, ts, text);
        blocks.add(block);
        return new ViewUpdate.Append(channelId, withLeadingBlank(block.render(directory)));
    }

    private ViewUpdate mutateMessage(String messageId, java.util.function.Consumer<MsgBlock> mutation) {
        Integer idx = indexByMessageId.get(messageId);
        if (idx == null || !(blocks.get(idx) instanceof MsgBlock block)) {
            return null; // unknown message
        }
        int[] range = lineRangeOfVisible(block); // null if hidden (blocked author)
        mutation.accept(block);
        if (range == null) {
            return null;
        }
        return new ViewUpdate.Replace(channelId, range[0], range[1], block.render(directory));
    }

    // ----- rendering (all over visibleBlocks) -------------------------------

    /** Blocks the viewer can see. Blocking only hides in DMs (leave a group to avoid someone). */
    private List<Block> visibleBlocks() {
        List<Block> visible = new ArrayList<>(blocks.size());
        for (Block b : blocks) {
            if (b instanceof MsgBlock m && isHiddenAuthor(m.authorId)) {
                continue;
            }
            visible.add(b);
        }
        return visible;
    }

    private boolean isHiddenAuthor(String authorId) {
        return type == ChannelType.DM && directory.isBlocked(authorId);
    }

    /** The full transcript buffer contents (what a Vim client loads on open). */
    public List<String> renderTranscript() {
        List<String> lines = new ArrayList<>(headerLines());
        for (Block b : visibleBlocks()) {
            lines.add("");
            lines.addAll(b.render(directory));
        }
        return lines;
    }

    /** The on-disk file: header (with #synced-seq) + blocks + compose marker. */
    public String renderFile() {
        StringBuilder sb = new StringBuilder();
        sb.append("#fschat v1\n");
        sb.append(channelLine()).append('\n');
        sb.append("#me id=").append(directory.meHandle()).append('\n');
        sb.append("#synced-seq ").append(syncedSeq).append('\n');
        for (Block b : visibleBlocks()) {
            sb.append('\n');
            for (String line : b.render(directory)) {
                sb.append(line).append('\n');
            }
        }
        sb.append("\n#=== compose (type below; :w or <CR> in this pane to send) ===\n");
        return sb.toString();
    }

    private List<String> headerLines() {
        return List.of("#fschat v1", channelLine(), "#me id=" + directory.meHandle());
    }

    private String channelLine() {
        StringBuilder sb = new StringBuilder("#channel id=").append(channelId).append(" type=").append(type);
        if (name != null) {
            sb.append(" name=\"").append(escape(name)).append('"');
        }
        return sb.toString();
    }

    /** Line range [header, last] of {@code target} in the VISIBLE transcript, or null if hidden. */
    private int[] lineRangeOfVisible(Block target) {
        int linesSoFar = HEADER_LINES;
        for (Block b : visibleBlocks()) {
            int header = linesSoFar + 2; // +1 blank separator, +1 header
            int bodyCount = b.render(directory).size() - 1;
            int last = header + bodyCount;
            linesSoFar = last;
            if (b == target) {
                return new int[]{header, last};
            }
        }
        return null;
    }

    private static List<String> withLeadingBlank(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size() + 1);
        out.add("");
        out.addAll(lines);
        return out;
    }

    private static String escape(String s) {
        return s.replace("\"", "'");
    }
}
