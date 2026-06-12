package dev.fschat.server.store;

import dev.fschat.protocol.model.ChannelMeta;
import dev.fschat.protocol.model.ChannelType;
import dev.fschat.protocol.model.UserRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class StoreTest {

    private Db db;
    private UserStore users;
    private ChannelStore channels;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        db = new Db(tmp.resolve("test.db"));
        users = new UserStore(db);
        channels = new ChannelStore(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void createsAndFindsUser() {
        UserStore.UserRow row = users.create("alice", "bcrypt-hash");
        assertThat(row.id()).matches("[0-9a-f]+"); // hex user id
        assertThat(users.findByUsername("alice")).contains(row);
        assertThat(users.findById(row.id())).contains(row);
        assertThat(users.findByUsername("nobody")).isEmpty();
    }

    @Test
    void allowsDuplicateUsernamesWithDistinctIds() {
        UserStore.UserRow a = users.create("alice", "h1");
        UserStore.UserRow b = users.create("alice", "h2");
        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(users.findAllByUsername("alice")).hasSize(2);
    }

    @Test
    void createsGroupWithMembers() {
        String a = users.create("alice", "h").id();
        String b = users.create("bob", "h").id();
        String c = users.create("carol", "h").id();

        ChannelMeta group = channels.create(ChannelType.GROUP, "trip", List.of(a, b, c));
        assertThat(group.type()).isEqualTo(ChannelType.GROUP);
        assertThat(group.name()).isEqualTo("trip");
        assertThat(group.members()).extracting(UserRef::username)
                .containsExactlyInAnyOrder("alice", "bob", "carol");
        assertThat(channels.isMember(group.id(), a)).isTrue();
        assertThat(channels.memberIds(group.id())).hasSize(3);
    }

    @Test
    void dedupesDmByMemberPairRegardlessOfOrder() {
        String a = users.create("alice", "h").id();
        String b = users.create("bob", "h").id();

        ChannelMeta dm = channels.create(ChannelType.DM, null, List.of(a, b));
        assertThat(channels.findDm(a, b)).map(ChannelMeta::id).contains(dm.id());
        assertThat(channels.findDm(b, a)).map(ChannelMeta::id).contains(dm.id());

        // A different pair has no DM yet.
        String c = users.create("carol", "h").id();
        assertThat(channels.findDm(a, c)).isEmpty();
    }

    @Test
    void addAndRemoveMembership() {
        String a = users.create("alice", "h").id();
        String b = users.create("bob", "h").id();
        ChannelMeta group = channels.create(ChannelType.GROUP, "trip", List.of(a));

        assertThat(channels.isMember(group.id(), b)).isFalse();
        channels.addMember(group.id(), b);
        assertThat(channels.isMember(group.id(), b)).isTrue();
        assertThat(channels.channelsForUser(b)).extracting(ChannelMeta::id).contains(group.id());

        channels.removeMember(group.id(), b);
        assertThat(channels.isMember(group.id(), b)).isFalse();
    }

    @Test
    void renamesAGroup() {
        String a = users.create("alice", "h").id();
        ChannelMeta group = channels.create(ChannelType.GROUP, "old", List.of(a));
        channels.rename(group.id(), "new-name");
        assertThat(channels.get(group.id())).map(ChannelMeta::name).contains("new-name");
    }

    @Test
    void blocksAreDirectional() {
        BlockStore blocks = new BlockStore(db);
        String a = users.create("alice", "h").id();
        String b = users.create("bob", "h").id();

        assertThat(blocks.blocks(a, b)).isFalse();
        blocks.block(a, b);
        assertThat(blocks.blocks(a, b)).isTrue();
        assertThat(blocks.blocks(b, a)).isFalse();           // directional
        assertThat(blocks.blockedBy(a)).containsExactly(b);
        assertThat(blocks.blockedBy(b)).isEmpty();

        blocks.block(a, b);                                  // idempotent
        assertThat(blocks.blockedBy(a)).containsExactly(b);

        blocks.unblock(a, b);
        assertThat(blocks.blocks(a, b)).isFalse();
    }
}
