package dev.fschat.daemon.queue;

import dev.fschat.protocol.model.OpType;
import dev.fschat.protocol.wire.WsMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineQueueTest {

    @Test
    void addRemoveSnapshotOrdering(@TempDir Path tmp) {
        OfflineQueue q = new OfflineQueue(tmp.resolve("queue.json"));
        q.add(new WsMessage.Op("op_1", "ch_1", OpType.POST, null, "one"));
        q.add(new WsMessage.Op("op_2", "ch_1", OpType.EDIT, "m_1", "two"));

        assertThat(q.snapshot()).extracting(WsMessage.Op::clientOpId).containsExactly("op_1", "op_2");
        q.remove("op_1");
        assertThat(q.snapshot()).extracting(WsMessage.Op::clientOpId).containsExactly("op_2");
    }

    @Test
    void persistsAcrossReload(@TempDir Path tmp) {
        Path file = tmp.resolve("queue.json");
        OfflineQueue q1 = new OfflineQueue(file);
        q1.add(new WsMessage.Op("op_1", "ch_1", OpType.POST, null, "survive me"));
        q1.add(new WsMessage.Op("op_2", "ch_1", OpType.DELETE, "m_9", null));

        OfflineQueue q2 = new OfflineQueue(file);
        assertThat(q2.size()).isEqualTo(2);
        assertThat(q2.snapshot()).extracting(WsMessage.Op::clientOpId).containsExactly("op_1", "op_2");
        assertThat(q2.snapshot().get(0).content()).isEqualTo("survive me");
    }
}
