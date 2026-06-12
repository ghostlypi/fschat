package dev.fschat.daemon.sync;

import dev.fschat.daemon.contacts.BlockBook;
import dev.fschat.daemon.contacts.ContactBook;
import dev.fschat.daemon.directory.Directory;
import dev.fschat.protocol.model.ChannelMeta;
import dev.fschat.protocol.model.ChannelType;
import dev.fschat.protocol.model.EventType;
import dev.fschat.protocol.model.UserRef;
import dev.fschat.protocol.wire.WsMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelViewTest {

    private static final String TS = "2026-06-11T09:00:00Z";

    @TempDir
    Path tmp;
    private Directory directory;

    private Directory directory() {
        if (directory == null) {
            directory = new Directory(new ContactBook(tmp.resolve("c.json")), new BlockBook(tmp.resolve("b.json")));
            directory.setMeHandle("usr_a");
        }
        return directory;
    }

    private ChannelView dmView() {
        ChannelMeta dm = new ChannelMeta("ch_1", ChannelType.DM, null,
                List.of(new UserRef("usr_a", "alice"), new UserRef("usr_b", "bob")), null);
        return new ChannelView(dm, directory());
    }

    private WsMessage.Event ev(long seq, EventType type, String mid, String author, String name, String content) {
        return new WsMessage.Event("ch_1", seq, type, mid, author, name, content, TS);
    }

    @Test
    void postRendersTranscriptWithOwnershipMarker() {
        ChannelView v = dmView();
        List<ViewUpdate> u1 = v.applyEvent(ev(1, EventType.POST, "m1", "usr_b", "bob", "hi"));
        List<ViewUpdate> u2 = v.applyEvent(ev(2, EventType.POST, "m2", "usr_a", "alice", "yo"));

        assertThat(u1).singleElement().isInstanceOf(ViewUpdate.Append.class);
        assertThat(((ViewUpdate.Append) u1.get(0)).lines())
                .containsExactly("", "#msg id=m1 author=bob:usr_b seq=1 ts=" + TS, "hi");
        assertThat(((ViewUpdate.Append) u2.get(0)).lines())
                .containsExactly("", "#msg id=m2 author=alice:usr_a seq=2 ts=" + TS, "yo");

        assertThat(v.renderTranscript()).containsExactly(
                "#fschat v1",
                "#channel id=ch_1 type=DM",
                "#me id=usr_a",
                "",
                "#msg id=m1 author=bob:usr_b seq=1 ts=" + TS,
                "hi",
                "",
                "#msg id=m2 author=alice:usr_a seq=2 ts=" + TS,
                "yo");
    }

    @Test
    void editReplacesTheRightLineRangeAndMarksEdited() {
        ChannelView v = dmView();
        v.applyEvent(ev(1, EventType.POST, "m1", "usr_b", "bob", "hi"));
        List<ViewUpdate> edit = v.applyEvent(ev(2, EventType.EDIT, "m1", "usr_b", "bob", "hi there"));

        assertThat(edit).singleElement().isInstanceOf(ViewUpdate.Replace.class);
        ViewUpdate.Replace r = (ViewUpdate.Replace) edit.get(0);
        // m1 occupies transcript lines 5 (header) and 6 (body).
        assertThat(r.fromLine()).isEqualTo(5);
        assertThat(r.toLine()).isEqualTo(6);
        assertThat(r.lines()).containsExactly(
                "#msg id=m1 author=bob:usr_b seq=1 ts=" + TS + " edited=true",
                "hi there");
    }

    @Test
    void deleteTombstonesTheMessage() {
        ChannelView v = dmView();
        v.applyEvent(ev(1, EventType.POST, "m1", "usr_a", "alice", "secret"));
        List<ViewUpdate> del = v.applyEvent(ev(2, EventType.DELETE, "m1", "usr_a", "alice", null));

        ViewUpdate.Replace r = (ViewUpdate.Replace) del.get(0);
        assertThat(r.lines()).containsExactly(
                "#msg id=m1 author=alice:usr_a seq=1 ts=" + TS + " deleted=true",
                "[deleted]");
        assertThat(v.renderTranscript()).contains("[deleted]");
    }

    @Test
    void multilineContentSpansBodyLines() {
        ChannelView v = dmView();
        List<ViewUpdate> u = v.applyEvent(ev(1, EventType.POST, "m1", "usr_b", "bob", "one\ntwo\nthree"));
        assertThat(((ViewUpdate.Append) u.get(0)).lines())
                .containsExactly("", "#msg id=m1 author=bob:usr_b seq=1 ts=" + TS, "one", "two", "three");
    }

    @Test
    void memberAddRendersSystemLine() {
        ChannelMeta group = new ChannelMeta("ch_1", ChannelType.GROUP, "trip",
                List.of(new UserRef("usr_a", "alice")), null);
        ChannelView v = new ChannelView(group, directory());
        List<ViewUpdate> u = v.applyEvent(ev(1, EventType.MEMBER_ADD, "mev_1", "usr_a", "alice", "dave"));
        assertThat(((ViewUpdate.Append) u.get(0)).lines())
                .containsExactly("", "#sys seq=1 ts=" + TS, "— alice added dave");
    }

    @Test
    void duplicateEventIsIgnored() {
        ChannelView v = dmView();
        v.applyEvent(ev(1, EventType.POST, "m1", "usr_b", "bob", "hi"));
        assertThat(v.applyEvent(ev(1, EventType.POST, "m1", "usr_b", "bob", "hi"))).isEmpty();
        assertThat(v.syncedSeq()).isEqualTo(1);
    }

    @Test
    void outOfOrderEventsAreBufferedThenAppliedInOrder() {
        ChannelView v = dmView();
        v.applyEvent(ev(1, EventType.POST, "m1", "usr_b", "bob", "one"));
        // seq 3 arrives before seq 2 -> buffered, no update yet.
        assertThat(v.applyEvent(ev(3, EventType.POST, "m3", "usr_b", "bob", "three"))).isEmpty();
        assertThat(v.syncedSeq()).isEqualTo(1);
        // seq 2 fills the gap -> both 2 and 3 apply.
        List<ViewUpdate> flushed = v.applyEvent(ev(2, EventType.POST, "m2", "usr_b", "bob", "two"));
        assertThat(flushed).hasSize(2);
        assertThat(v.syncedSeq()).isEqualTo(3);
        assertThat(v.renderTranscript()).filteredOn(s -> s.equals("two") || s.equals("three"))
                .containsExactly("two", "three");
    }

    @Test
    void blockedAuthorIsHiddenAndLineRangesStayCorrect() {
        ChannelView v = dmView();
        // bob is blocked before any messages
        directory().blocks().block("usr_b");

        // bob's post is applied to the model but produces no UI update
        assertThat(v.applyEvent(ev(1, EventType.POST, "m1", "usr_b", "bob", "hidden"))).isEmpty();
        // alice's post is the first VISIBLE block
        v.applyEvent(ev(2, EventType.POST, "m2", "usr_a", "alice", "shown"));

        assertThat(v.renderTranscript()).doesNotContain("hidden").contains("shown");

        // editing alice's message computes its range over the VISIBLE list (m1 hidden):
        // header lines 1-3, blank 4, m2 header 5, body 6.
        List<ViewUpdate> edit = v.applyEvent(ev(3, EventType.EDIT, "m2", "usr_a", "alice", "shown!"));
        ViewUpdate.Replace r = (ViewUpdate.Replace) edit.get(0);
        assertThat(r.fromLine()).isEqualTo(5);
        assertThat(r.toLine()).isEqualTo(6);

        // unblocking restores bob's message
        directory().blocks().unblock("usr_b");
        assertThat(v.renderTranscript()).contains("hidden");
    }

    @Test
    void nicknameRendersAsBareLabel() {
        ChannelView v = dmView();
        directory().contacts().upsert("usr_b", "bob", "Bobby");
        directory().recompute();
        List<ViewUpdate> u = v.applyEvent(ev(1, EventType.POST, "m1", "usr_b", "bob", "hi"));
        assertThat(((ViewUpdate.Append) u.get(0)).lines())
                .containsExactly("", "#msg id=m1 author=Bobby seq=1 ts=" + TS, "hi");
    }

    @Test
    void collidingContactLabelsDisambiguateWithHexid() {
        ChannelView v = dmView();
        directory().contacts().upsert("usr_b", "bob", null);
        directory().contacts().upsert("usr_c", "bob", null); // same label "bob"
        directory().recompute();
        List<ViewUpdate> u = v.applyEvent(ev(1, EventType.POST, "m1", "usr_b", "bob", "hi"));
        assertThat(((ViewUpdate.Append) u.get(0)).lines())
                .contains("#msg id=m1 author=bob:usr_b seq=1 ts=" + TS);
    }

    @Test
    void memberLeaveRendersRemovedVsLeft() {
        ChannelMeta group = new ChannelMeta("ch_1", ChannelType.GROUP, "g",
                List.of(new UserRef("usr_a", "alice")), null);
        ChannelView v = new ChannelView(group, directory());
        // alice removes bob: content (bob) != author (alice) -> "removed"
        List<ViewUpdate> removed = v.applyEvent(ev(1, EventType.MEMBER_LEAVE, "mev1", "usr_a", "alice", "bob"));
        assertThat(((ViewUpdate.Append) removed.get(0)).lines()).contains("— alice removed bob");
        // alice leaves: content equals author -> "left"
        List<ViewUpdate> left = v.applyEvent(ev(2, EventType.MEMBER_LEAVE, "mev2", "usr_a", "alice", "alice"));
        assertThat(((ViewUpdate.Append) left.get(0)).lines()).contains("— alice left");
    }

    @Test
    void blockedAuthorStillShowsInGroups() {
        ChannelMeta group = new ChannelMeta("ch_1", ChannelType.GROUP, "g",
                List.of(new UserRef("usr_a", "alice"), new UserRef("usr_b", "bob")), null);
        ChannelView v = new ChannelView(group, directory());
        directory().blocks().block("usr_b");
        // In a GROUP, a blocked author's message is NOT hidden.
        List<ViewUpdate> u = v.applyEvent(ev(1, EventType.POST, "m1", "usr_b", "bob", "group msg"));
        assertThat(u).singleElement().isInstanceOf(ViewUpdate.Append.class);
        assertThat(v.renderTranscript()).contains("group msg");
    }

    @Test
    void renameUpdatesNameAndAddsSystemLine() {
        ChannelMeta group = new ChannelMeta("ch_1", ChannelType.GROUP, "old",
                List.of(new UserRef("usr_a", "alice")), null);
        ChannelView v = new ChannelView(group, directory());
        // RENAME produces no incremental update (the SyncEngine issues a full re-render).
        assertThat(v.applyEvent(ev(1, EventType.RENAME, "mev1", "usr_a", "alice", "new-name"))).isEmpty();
        assertThat(v.name()).isEqualTo("new-name");
        assertThat(v.renderTranscript())
                .anyMatch(l -> l.contains("name=\"new-name\""))
                .anyMatch(l -> l.contains("renamed to new-name"));
    }
}
