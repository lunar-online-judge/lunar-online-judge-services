package io.lunar.common.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * 统一事件信封（docs/api.md"事件信封"）：上线消息体携带 {@code eventId / eventType / aggregateId /
 * occurredAt / schemaVersion / traceId} 六个字段，业务数据统一放在内层 {@code payload}。
 *
 * 为什么必须有它：消费端"按 eventId 幂等"是仓库的硬约定，而此前各服务的 Outbox 只把内层 payload 发上线，
 * eventId 不随消息传输（docs/api.md 已记录该差距），因此 judge 的消费者没有任何去重、重复投递会重复评测。
 * 信封把 eventId 送到消费端，幂等才有可能实现。
 *
 * 生产者用 {@link #toJson} 发包，消费者用 {@link EventMessages} 解包——后者同时兼容旧的裸 payload 格式，
 * 因为生产者与消费者无法在同一时刻发布，灰度期内两种格式必然共存。
 */
public record EventEnvelope(String eventId,
                            String eventType,
                            String aggregateId,
                            Instant occurredAt,
                            int schemaVersion,
                            String traceId,
                            JsonNode payload) {

    /** 当前信封版本：只有在字段语义变更时才递增，消费端据此决定兼容分支。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final String FIELD_EVENT_ID = "eventId";
    public static final String FIELD_EVENT_TYPE = "eventType";
    public static final String FIELD_AGGREGATE_ID = "aggregateId";
    public static final String FIELD_OCCURRED_AT = "occurredAt";
    public static final String FIELD_SCHEMA_VERSION = "schemaVersion";
    public static final String FIELD_TRACE_ID = "traceId";
    public static final String FIELD_PAYLOAD = "payload";

    /**
     * 压缩构造器：信封一旦缺字段，消费端的幂等与追踪就静默失效——这类错误在运行时极难定位，
     * 因此在这里直接拒绝，让生产者的编码错误在构造点就暴露。
     */
    public EventEnvelope {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("事件信封缺少 eventId");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("事件信封缺少 eventType");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("事件信封缺少 aggregateId");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("事件信封缺少 occurredAt");
        }
        if (payload == null || payload.isNull()) {
            throw new IllegalArgumentException("事件信封缺少 payload");
        }
    }

    /** 完整构造：{@code occurredAt} 与 {@code schemaVersion} 由 Outbox 行携带，保持"落库即事实"。 */
    public static EventEnvelope of(String eventId, String eventType, String aggregateId, Instant occurredAt,
                                   int schemaVersion, String traceId, JsonNode payload) {
        return new EventEnvelope(eventId, eventType, aggregateId, occurredAt, schemaVersion, traceId, payload);
    }

    /** 无 Outbox 行的生产者（如 judge 直发 judged）使用：时间取当前时刻、版本取当前信封版本。 */
    public static EventEnvelope ofNow(String eventId, String eventType, String aggregateId,
                                      String traceId, JsonNode payload) {
        return new EventEnvelope(eventId, eventType, aggregateId, Instant.now(),
                CURRENT_SCHEMA_VERSION, traceId, payload);
    }

    /**
     * 序列化为上线消息体。{@code occurredAt} 用 ISO-8601 字符串（跨服务可读、无时区歧义）；
     * {@code traceId} 为空时整个字段省略——RocketMQ 拒绝空属性值，历史上有过"设了空 traceId 导致
     * 事件永远发不出去"的实测教训（见 submission 的 OutboxPublisher 注释）。
     */
    public String toJson(ObjectMapper objectMapper) throws JsonProcessingException {
        ObjectNode envelopeNode = objectMapper.createObjectNode();
        envelopeNode.put(FIELD_EVENT_ID, eventId);
        envelopeNode.put(FIELD_EVENT_TYPE, eventType);
        envelopeNode.put(FIELD_AGGREGATE_ID, aggregateId);
        envelopeNode.put(FIELD_OCCURRED_AT, occurredAt.toString());
        envelopeNode.put(FIELD_SCHEMA_VERSION, schemaVersion);
        if (traceId != null && !traceId.isBlank()) {
            envelopeNode.put(FIELD_TRACE_ID, traceId);
        }
        envelopeNode.set(FIELD_PAYLOAD, payload);
        return objectMapper.writeValueAsString(envelopeNode);
    }
}
