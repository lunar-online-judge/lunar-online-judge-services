package io.lunar.common.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 信封的兼容语义是这个类存在的全部理由：灰度期内新旧两种消息格式会到达同一个消费组，
 * 消费者认错一种就会丢掉整条业务数据，因此每种形态都必须有用例盯着。
 */
class EventMessagesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void envelopeIsUnwrappedToInnerPayload() throws Exception {
        String message = EventEnvelope.of("evt-1", "lunar-submission-judged", "sub-1",
                        Instant.parse("2026-10-13T10:00:00Z"), EventEnvelope.CURRENT_SCHEMA_VERSION, "trace-1",
                        objectMapper.readTree("{\"submissionId\":\"sub-1\",\"verdict\":\"ACCEPTED\"}"))
                .toJson(objectMapper);

        JsonNode payload = EventMessages.payloadOf(objectMapper, message);

        assertThat(payload.get("submissionId").asText()).isEqualTo("sub-1");
        assertThat(payload.get("verdict").asText()).isEqualTo("ACCEPTED");
        assertThat(EventMessages.eventIdOf(objectMapper, message)).isEqualTo("evt-1");
        assertThat(EventMessages.traceIdOf(objectMapper, message)).isEqualTo("trace-1");
    }

    /** 灰度期内的旧消息：整条消息就是内层 payload，不能要求它必须有 eventId。 */
    @Test
    void legacyBarePayloadIsStillReadable() throws Exception {
        String legacyMessage = "{\"submissionId\":\"sub-9\",\"verdict\":\"WRONG_ANSWER\"}";

        JsonNode payload = EventMessages.payloadOf(objectMapper, legacyMessage);

        assertThat(payload.get("submissionId").asText()).isEqualTo("sub-9");
        assertThat(payload.get("verdict").asText()).isEqualTo("WRONG_ANSWER");
        // 旧格式没有 eventId：调用方据此退化为业务侧终态保护幂等，而不是把整条消息当垃圾丢掉
        assertThat(EventMessages.eventIdOf(objectMapper, legacyMessage)).isNull();
    }

    /**
     * 关键反例：旧格式的内层 payload 自己带一个叫 payload 的业务字段。
     * 若只凭"有 payload 字段"判定信封，这条消息会被剥掉外壳、业务数据全丢。
     */
    @Test
    void legacyPayloadWithPayloadFieldIsNotMistakenForEnvelope() throws Exception {
        String legacyMessage = "{\"submissionId\":\"sub-7\",\"payload\":{\"nested\":true}}";

        JsonNode messageNode = EventMessages.parse(objectMapper, legacyMessage);

        assertThat(EventMessages.isEnvelope(messageNode)).isFalse();
        assertThat(EventMessages.payloadOf(objectMapper, legacyMessage).get("submissionId").asText())
                .isEqualTo("sub-7");
    }

    /** traceId 为空时必须整个字段省略：RocketMQ 拒绝空属性值，历史上有过事件卡死的实测教训。 */
    @Test
    void blankTraceIdIsOmittedFromEnvelope() throws Exception {
        String message = EventEnvelope.ofNow("evt-2", "lunar-risk-event-created", "risk-1", null,
                        objectMapper.readTree("{\"eventType\":\"FOCUS_LOST\"}"))
                .toJson(objectMapper);

        assertThat(objectMapper.readTree(message).has(EventEnvelope.FIELD_TRACE_ID)).isFalse();
        assertThat(EventMessages.traceIdOf(objectMapper, message)).isNull();
    }
}
