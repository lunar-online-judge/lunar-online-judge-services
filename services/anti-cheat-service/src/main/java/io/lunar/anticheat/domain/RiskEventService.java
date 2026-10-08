package io.lunar.anticheat.domain;

import io.lunar.anticheat.infra.AuditClient;
import io.lunar.anticheat.infra.OutboxEventEntity;
import io.lunar.anticheat.infra.OutboxEventMapper;
import io.lunar.anticheat.infra.RiskEventEntity;
import io.lunar.anticheat.infra.RiskEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 风险事件与人工审核（docs/exam-process.md §6）：
 * 上报宽容接收（severity 服务端计算，未知类型 MEDIUM 保守收下）；
 * 审核状态机 OPEN → ACKNOWLEDGED → CONFIRMED / DISMISSED（条件更新防并发，终态不可再动）；
 * 事件入库即写 Outbox（lunar-risk-event-created，gateway WebSocket 告警的未来消费方）；
 * 审计：audit-service 未建成，CONFIRM/DISMISS 先落结构化日志。
 */
@Service
public class RiskEventService {

    private static final Logger log = LoggerFactory.getLogger(RiskEventService.class);

    public record ReportedEvent(String eventId, String riskEventId, String eventType, String severity,
                                Instant occurredAt) {}

    public record RiskEventView(String eventId, String contestId, String studentId, String problemId,
                                String eventType, String severity, String detail, Instant occurredAt,
                                Instant reportedAt, String reviewStatus, String reviewedBy,
                                Instant reviewedAt, String reviewNote) {}

    public record ReviewPage(java.util.List<RiskEventView> items, long total, long page, long pageSize) {}

    private final RiskEventMapper riskEventMapper;
    private final OutboxEventMapper outboxMapper;
    private final AuditClient auditClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RiskEventService(RiskEventMapper riskEventMapper, OutboxEventMapper outboxMapper,
                            AuditClient auditClient) {
        this.riskEventMapper = riskEventMapper;
        this.outboxMapper = outboxMapper;
        this.auditClient = auditClient;
    }

    /** 上报风险事件（考生客户端自动上报，studentId = JWT sub）。 */
    @Transactional
    public ReportedEvent report(String contestId, String studentId, String eventType,
                                String detail, Instant occurredAt, String problemId, String traceId) {
        RiskEventEntity entity = new RiskEventEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setContestId(contestId);
        entity.setStudentId(studentId);
        entity.setProblemId(problemId);
        entity.setEventType(eventType);
        entity.setSeverity(RiskRules.severityOf(eventType));
        entity.setDetail(detail == null ? null : detail.substring(0, Math.min(detail.length(), 2000)));
        entity.setOccurredAt(occurredAt == null ? Instant.now() : occurredAt);
        entity.setReportedAt(Instant.now());
        entity.setReviewStatus("OPEN");
        riskEventMapper.insert(entity);

        writeOutbox(entity, traceId);
        log.info("AUDIT event=RISK_EVENT_REPORTED riskEventId={} contestId={} studentId={} type={} severity={}",
                entity.getId(), contestId, studentId, eventType, entity.getSeverity());
        return new ReportedEvent(entity.getId(), entity.getId(), eventType, entity.getSeverity(), entity.getOccurredAt());
    }

    /** 分页查询（监考/管理视图）：按竞赛、学生、等级、审核状态过滤。 */
    public ReviewPage list(String contestId, String studentId, String severity,
                           String reviewStatus, long page, long pageSize) {
        LambdaQueryWrapper<RiskEventEntity> query = new LambdaQueryWrapper<RiskEventEntity>()
                .orderByDesc(RiskEventEntity::getReportedAt);
        if (contestId != null && !contestId.isBlank()) {
            query.eq(RiskEventEntity::getContestId, contestId);
        }
        if (studentId != null && !studentId.isBlank()) {
            query.eq(RiskEventEntity::getStudentId, studentId);
        }
        if (severity != null && !severity.isBlank()) {
            query.eq(RiskEventEntity::getSeverity, severity);
        }
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            query.eq(RiskEventEntity::getReviewStatus, reviewStatus);
        }
        Page<RiskEventEntity> result = riskEventMapper.selectPage(new Page<>(page, pageSize), query);
        return new ReviewPage(result.getRecords().stream().map(this::toView).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 审核动作：ACKNOWLEDGE / CONFIRM / DISMISS（状态机 OPEN → ACKNOWLEDGED → CONFIRMED|DISMISSED）。 */
    @Transactional
    public RiskEventView review(String eventId, String action, String note, String reviewerId,
                                String operatorToken) {
        RiskEventEntity entity = riskEventMapper.selectById(eventId);
        if (entity == null) {
            throw new ReviewException.NotFound();
        }
        String from = entity.getReviewStatus();
        String to = switch (action) {
            case "ACKNOWLEDGE" -> "ACKNOWLEDGED";
            case "CONFIRM" -> "CONFIRMED";
            case "DISMISS" -> "DISMISSED";
            default -> throw new ReviewException.InvalidTransition(from, action);
        };
        boolean legal = switch (from) {
            case "OPEN" -> "ACKNOWLEDGED".equals(to) || "CONFIRMED".equals(to) || "DISMISSED".equals(to);
            case "ACKNOWLEDGED" -> "CONFIRMED".equals(to) || "DISMISSED".equals(to);
            default -> false; // CONFIRMED/DISMISSED 为终态
        };
        if (!legal) {
            throw new ReviewException.InvalidTransition(from, action);
        }
        entity.setReviewStatus(to);
        entity.setReviewedBy(reviewerId);
        entity.setReviewedAt(Instant.now());
        entity.setReviewNote(note);
        riskEventMapper.updateById(entity);
        log.info("AUDIT event=RISK_EVENT_REVIEWED riskEventId={} {}->{} by={} note={}",
                eventId, from, to, reviewerId, note);
        // 审计账本（尽力而为：audit-service 暂不可用时不阻断审核）
        try {
            auditClient.record("RISK_EVENT_REVIEWED", "RISK_EVENT", eventId, entity.getContestId(),
                    from + "->" + to + (note == null || note.isBlank() ? "" : " note=" + note),
                    null, operatorToken);
        } catch (Exception e) {
            log.warn("审计写入失败（不影响审核结果）：riskEventId={} err={}", eventId, e.getMessage());
        }
        return toView(entity);
    }

    private void writeOutbox(RiskEventEntity entity, String traceId) {
        try {
            OutboxEventEntity event = new OutboxEventEntity();
            event.setId(UUID.randomUUID().toString());
            event.setEventId(UUID.randomUUID().toString());
            event.setEventType("lunar-risk-event-created");
            event.setAggregateId(entity.getId());
            event.setSchemaVersion(1);
            event.setTraceId(traceId);
            event.setPayload(objectMapper.writeValueAsString(Map.of(
                    "riskEventId", entity.getId(),
                    "contestId", entity.getContestId(),
                    "studentId", entity.getStudentId(),
                    "eventType", entity.getEventType(),
                    "severity", entity.getSeverity(),
                    "occurredAt", entity.getOccurredAt().toString())));
            event.setOccurredAt(Instant.now());
            event.setCreatedAt(Instant.now());
            outboxMapper.insert(event);
        } catch (Exception e) {
            throw new IllegalStateException("风险事件 Outbox 序列化失败", e);
        }
    }

    private RiskEventView toView(RiskEventEntity e) {
        return new RiskEventView(e.getId(), e.getContestId(), e.getStudentId(), e.getProblemId(),
                e.getEventType(), e.getSeverity(), e.getDetail(), e.getOccurredAt(), e.getReportedAt(),
                e.getReviewStatus(), e.getReviewedBy(), e.getReviewedAt(), e.getReviewNote());
    }
}
