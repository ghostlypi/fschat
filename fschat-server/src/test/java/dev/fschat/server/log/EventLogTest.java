package dev.fschat.server.log;

import dev.fschat.protocol.model.ChannelType;
import dev.fschat.protocol.model.EventType;
import dev.fschat.server.log.EventLog.AppendOutcome;
import dev.fschat.server.log.EventLog.LogEvent;
import dev.fschat.server.store.ChannelStore;
import dev.fschat.server.store.Db;
import dev.fschat.server.store.UserStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class EventLogTest {

    private Db db;
    private EventLog log;
    private String channelId;
    private String alice;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        db = new Db(tmp.resolve("test.db"));
        log = new EventLog(db);
        UserStore users = new UserStore(db);
        ChannelStore channels = new ChannelStore(db);
        alice = users.create("alice", "hash").id();
        String bob = users.create("bob", "hash").id();
        channelId = channels.create(ChannelType.DM, null, List.of(alice, bob)).id();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void appendAllocatesMonotonicSeqAndGeneratesMessageId() {
        AppendOutcome a = log.append(channelId, EventType.POST, alice, null, "one", "op_1");
        AppendOutcome b = log.append(channelId, EventType.POST, alice, null, "two", "op_2");

        assertThat(a.wasDuplicate()).isFalse();
        assertThat(a.event().seq()).isEqualTo(1L);
        assertThat(a.event().messageId()).startsWith("m_");
        assertThat(b.event().seq()).isEqualTo(2L);
        assertThat(a.event().authorName()).isEqualTo("alice");
    }

    @Test
    void duplicateClientOpReturnsOriginalWithoutNewRow() {
        AppendOutcome first = log.append(channelId, EventType.POST, alice, null, "hello", "op_dup");
        AppendOutcome again = log.append(channelId, EventType.POST, alice, null, "hello-resent", "op_dup");

        assertThat(again.wasDuplicate()).isTrue();
        assertThat(again.event().seq()).isEqualTo(first.event().seq());
        assertThat(again.event().messageId()).isEqualTo(first.event().messageId());
        assertThat(again.event().content()).isEqualTo("hello"); // original content, not the resend
        assertThat(log.headSeq(channelId)).isEqualTo(1L);
    }

    @Test
    void readFromReturnsOnlyEventsAfterSeq() {
        log.append(channelId, EventType.POST, alice, null, "one", "op_1");
        log.append(channelId, EventType.POST, alice, null, "two", "op_2");
        log.append(channelId, EventType.POST, alice, null, "three", "op_3");

        List<LogEvent> all = log.readFrom(channelId, 0);
        List<LogEvent> tail = log.readFrom(channelId, 2);

        assertThat(all).extracting(LogEvent::content).containsExactly("one", "two", "three");
        assertThat(tail).extracting(LogEvent::seq).containsExactly(3L);
    }

    @Test
    void editAndDeleteAppendNewSeqsForSameMessage() {
        String mid = log.append(channelId, EventType.POST, alice, null, "draft", "op_1").event().messageId();
        log.append(channelId, EventType.EDIT, alice, mid, "final", "op_2");

        assertThat(log.isLiveMessage(channelId, mid)).isTrue();
        assertThat(log.authorOfMessage(channelId, mid)).contains(alice);

        log.append(channelId, EventType.DELETE, alice, mid, null, "op_3");
        assertThat(log.isLiveMessage(channelId, mid)).isFalse();
        assertThat(log.headSeq(channelId)).isEqualTo(3L);
    }

    @Test
    void concurrentAppendsGetUniqueIncreasingSeqs() throws InterruptedException {
        int n = 64;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReferenceArray<Long> seqs = new AtomicReferenceArray<>(n);
        try {
            for (int i = 0; i < n; i++) {
                int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        AppendOutcome out = log.append(
                                channelId, EventType.POST, alice, null, "m" + idx, "op_" + idx);
                        seqs.set(idx, out.event().seq());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        List<Long> collected = IntStream.range(0, n).mapToObj(seqs::get).collect(Collectors.toList());
        assertThat(collected).doesNotContainNull();
        assertThat(collected).doesNotHaveDuplicates();
        assertThat(collected.stream().sorted().collect(Collectors.toList()))
                .isEqualTo(IntStream.rangeClosed(1, n).mapToObj(Long::valueOf).collect(Collectors.toList()));
        assertThat(log.headSeq(channelId)).isEqualTo((long) n);
    }
}
