package io.lunar.anticheat.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** anti_cheat_schema.outbox_events 事件发件箱。 */
@TableName("anti_cheat_schema.outbox_events")
public class OutboxEventEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String eventId;

    private String eventType;

    private String aggregateId;

    private Integer schemaVersion;

    private String traceId;

    private String payload;

    private Instant occurredAt;

    private Instant createdAt;

    private Instant publishedAt;

    /** 连续投递失败次数（BE-07 退避/死信）。 */
    private Integer attempts;

    /** 下次可投递时间（指数退避，BE-07）。 */
    private Instant nextAttemptAt;

    /** 转入死信的时间（超过上限不再自动重试，等待人工重放，BE-07）。 */
    private Instant deadLetteredAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public Instant getDeadLetteredAt() { return deadLetteredAt; }
    public void setDeadLetteredAt(Instant deadLetteredAt) { this.deadLetteredAt = deadLetteredAt; }
}
