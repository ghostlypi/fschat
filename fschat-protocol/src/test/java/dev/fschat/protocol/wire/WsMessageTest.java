package dev.fschat.protocol.wire;

import dev.fschat.protocol.Json;
import dev.fschat.protocol.model.ChannelMeta;
import dev.fschat.protocol.model.ChannelType;
import dev.fschat.protocol.model.EventType;
import dev.fschat.protocol.model.OpType;
import dev.fschat.protocol.model.UserRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class WsMessageTest {

    static Stream<WsMessage> allMessageTypes() {
        return Stream.of(
                new WsMessage.Auth("jwt-token-abc"),
                new WsMessage.AuthOk("usr_alice", "alice", List.of(
                        new ChannelMeta("ch_1", ChannelType.DM, null,
                                List.of(new UserRef("usr_alice", "alice"), new UserRef("usr_bob", "bob")), null),
                        new ChannelMeta("ch_2", ChannelType.GROUP, "weekend-trip",
                                List.of(new UserRef("usr_alice", "alice")), null))),
                new WsMessage.Subscribe("ch_1", 142L),
                new WsMessage.BackfillDone("ch_1", 142L),
                new WsMessage.Op("op_1", "ch_1", OpType.POST, null, "hello world"),
                new WsMessage.Op("op_2", "ch_1", OpType.EDIT, "m_2", "edited text"),
                new WsMessage.Op("op_3", "ch_1", OpType.DELETE, "m_3", null),
                new WsMessage.Ack("op_1", "ch_1", "m_4", 143L),
                new WsMessage.Event("ch_1", 143L, EventType.POST, "m_4", "usr_alice", "Alice",
                        "hello world", "2026-06-11T09:10:00Z"),
                new WsMessage.Event("ch_1", 144L, EventType.DELETE, "m_3", "usr_alice", "Alice",
                        null, "2026-06-11T09:11:00Z"),
                new WsMessage.CreateChannel("req_1", ChannelType.GROUP, "weekend-trip",
                        List.of("bob", "carol")),
                new WsMessage.ChannelCreated("req_1", new ChannelMeta("ch_2", ChannelType.GROUP,
                        "weekend-trip", List.of(new UserRef("usr_alice", "alice")), null)),
                new WsMessage.AddMember("req_2", "ch_2", "dave"),
                new WsMessage.RemoveMember("req_2b", "ch_2", "dave"),
                new WsMessage.Rename("req_3", "ch_2", "weekend-trip-final"),
                new WsMessage.Block("req_4", "f6e668"),
                new WsMessage.Unblock("req_5", "f6e668"),
                new WsMessage.BlockAck("req_4", "f6e668", true),
                new WsMessage.Event("ch_2", 200L, EventType.RENAME, "mev_2", "usr_alice", "Alice",
                        "weekend-trip-final", "2026-06-11T10:00:00Z"),
                new WsMessage.Error("NOT_OWNER", "op_2", "cannot edit m_2"),
                new WsMessage.Ping(7L),
                new WsMessage.Pong(7L));
    }

    @ParameterizedTest
    @MethodSource("allMessageTypes")
    void roundTripsThroughJson(WsMessage original) {
        String json = Json.write(original);
        WsMessage back = Json.read(json, WsMessage.class);
        assertThat(back)
                .isInstanceOf(original.getClass())
                .isEqualTo(original);
    }

    @Test
    void writesDiscriminatorAndOmitsNulls() {
        String json = Json.write(new WsMessage.Op("op_3", "ch_1", OpType.DELETE, null, null));
        assertThat(json).contains("\"t\":\"op\"");
        // NON_NULL inclusion: messageId and content are absent, not null literals.
        assertThat(json).doesNotContain("messageId");
        assertThat(json).doesNotContain("content");
        assertThat(json).contains("\"op\":\"DELETE\"");
    }

    @Test
    void deserializesByDiscriminator() {
        String json = "{\"t\":\"subscribe\",\"channelId\":\"ch_9\",\"fromSeq\":5}";
        WsMessage msg = Json.read(json, WsMessage.class);
        assertThat(msg).isEqualTo(new WsMessage.Subscribe("ch_9", 5L));
    }

    @Test
    void toleratesUnknownForwardCompatField() {
        String json = "{\"t\":\"ping\",\"nonce\":1,\"futureField\":\"ignored\"}";
        WsMessage msg = Json.read(json, WsMessage.class);
        assertThat(msg).isEqualTo(new WsMessage.Ping(1L));
    }
}
