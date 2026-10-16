package io.lunar.audit.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** audit_schema.audit_logs 审计账本（追加式，无 update/delete）。 */
@TableName("audit_schema.audit_logs")
public class AuditLogEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String action;

    private String actorId;

    private String actorRole;

    /** 服务身份（SERVICE 令牌的 sub，如 contest-service）；用户动作为 null。 */
    private String serviceId;

    private String targetType;

    private String targetId;

    private String contestId;

    private String detail;

    private String traceId;

    /** 来源事件的 eventId（BE-07 异步消费幂等键，唯一索引 uk_audit_logs_event_id）；同步写入为 null。 */
    private String eventId;

    /** 动作实际发生时间（事件信封 occurredAt，BE-07）：投递可能延迟，入账时间见 createdAt。 */
    private Instant originalOccurredAt;

    private Instant occurredAt;

    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getActorRole() { return actorRole; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getContestId() { return contestId; }
    public void setContestId(String contestId) { this.contestId = contestId; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public Instant getOriginalOccurredAt() { return originalOccurredAt; }
    public void setOriginalOccurredAt(Instant originalOccurredAt) { this.originalOccurredAt = originalOccurredAt; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}