package io.lunar.anticheat.domain;

import io.lunar.anticheat.domain.dto.ReportedEvent;
import io.lunar.anticheat.domain.dto.ReviewPage;
import io.lunar.anticheat.domain.dto.RiskEventView;
import io.lunar.anticheat.infra.OutboxEventEntity;
import io.lunar.anticheat.infra.OutboxEventMapper;
import io.lunar.anticheat.infra.RiskEventEntity;
import io.lunar.anticheat.infra.RiskEventMapper;
import io.lunar.common.audit.AuditRecorder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 风险事件与人工审核（docs/exam-process.md §6）：
 * 上报宽容接收（severity 服务端计算，未知类型 MEDIUM 保守收下）；
 * 审核状态机 OPEN → ACKNOWLEDGED → CONFIRMED / DISMISSED：写入走"前态条件更新"（CAS），
 * 并发终审最多一个成功，终态不可再动；
 * 事件入库即写 Outbox（lunar-risk-event-created，gateway WebSocket 告警的未来消费方）；
 * 审计：CONFIRM/DISMISS 经 audit-service 账本留痕（尽力而为，失败不阻断审核）。
 */
@Service
public class RiskEventService {

    private static final Logger log = LoggerFactory.getLogger(RiskEventService.class);

    /** 明细入库前的最大长度（超出截断，避免恶意超长上报撑爆存储）。 */
    private static final int MAX_DETAIL_LENGTH = 2000;

    /** 审核原因最大长度（BE-03）：终态结论必填，且有上限。 */
    private static final int MAX_REVIEW_NOTE_LENGTH = 500;

    /**
     * 日志按纯文本处理（BE-03）：换行/制表符会让一条日志裂成多行、污染结构化字段，
     * 这里统一折成空格并截断——注意只影响日志，入库内容保持原样。
     */
    private static String sanitizeForLog(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("[\\r\\n\\t]+", " ");
        return flattened.length() > MAX_REVIEW_NOTE_LENGTH
                ? flattened.substring(0, MAX_REVIEW_NOTE_LENGTH) : flattened;
    }

    private final RiskEventMapper riskEventMapper;
    private final OutboxEventMapper outboxMapper;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RiskEventService(RiskEventMapper riskEventMapper, OutboxEventMapper outboxMapper,
                            AuditRecorder auditRecorder) {
        this.riskEventMapper = riskEventMapper;
        this.outboxMapper = outboxMapper;
        this.auditRecorder = auditRecorder;
    }

    /** 上报风险事件（考生客户端自动上报，studentId = JWT sub）。 */
    @Transactional
    public ReportedEvent report(String contestId, String studentId, String eventType,
                                String detail, Instant occurredAt, String problemId, String traceId) {
        RiskEventEntity riskEvent = new RiskEventEntity();
        riskEvent.setId(UUID.randomUUID().toString());
        riskEvent.setContestId(contestId);
        riskEvent.setStudentId(studentId);
        riskEvent.setProblemId(problemId);
        riskEvent.setEventType(eventType);
        riskEvent.setSeverity(RiskRules.severityOf(eventType));
        riskEvent.setDetail(detail == null ? null : detail.substring(0, Math.min(detail.length(), MAX_DETAIL_LENGTH)));
        riskEvent.setOccurredAt(occurredAt == null ? Instant.now() : occurredAt);
        riskEvent.setReportedAt(Instant.now());
        riskEvent.setReviewStatus("OPEN");
        riskEventMapper.insert(riskEvent);

        writeOutbox(riskEvent, traceId);
        log.info("AUDIT event=RISK_EVENT_REPORTED riskEventId={} contestId={} studentId={} type={} severity={}",
                riskEvent.getId(), contestId, studentId, eventType, riskEvent.getSeverity());
        return new ReportedEvent(riskEvent.getId(), riskEvent.getId(), eventType, riskEvent.getSeverity(),
                riskEvent.getOccurredAt());
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
        RiskEventEntity riskEvent = riskEventMapper.selectById(eventId);
        if (riskEvent == null) {
            throw new ReviewException.NotFound();
        }
        String from = riskEvent.getReviewStatus();
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
        // 终态结论必须给出可读原因（BE-03）：处置要可追溯，且长度有上限
        boolean terminal = "CONFIRMED".equals(to) || "DISMISSED".equals(to);
        if (terminal && (note == null || note.isBlank())) {
            throw new ReviewException.InvalidReason("最终结论必须填写原因");
        }
        if (note != null && note.length() > MAX_REVIEW_NOTE_LENGTH) {
            throw new ReviewException.InvalidReason("原因长度不能超过 " + MAX_REVIEW_NOTE_LENGTH + " 字符");
        }
        Instant reviewedAt = Instant.now();
        // 条件更新（CAS）：仅当前态仍是 from 时才写入——两个并发终审最多一个成功，避免后提交者覆盖先提交的终态
        LambdaUpdateWrapper<RiskEventEntity> update = new LambdaUpdateWrapper<RiskEventEntity>()
                .eq(RiskEventEntity::getId, eventId)
                .eq(RiskEventEntity::getReviewStatus, from)
                .set(RiskEventEntity::getReviewStatus, to)
                .set(RiskEventEntity::getReviewedBy, reviewerId)
                .set(RiskEventEntity::getReviewedAt, reviewedAt)
                .set(RiskEventEntity::getReviewNote, note);
        if (riskEventMapper.update(null, update) == 0) {
            // 前态已被其他请求改走：带上当前实际状态返回非法流转
            RiskEventEntity current = riskEventMapper.selectById(eventId);
            throw new ReviewException.InvalidTransition(
                    current == null ? from : current.getReviewStatus(), action);
        }
        riskEvent.setReviewStatus(to);
        riskEvent.setReviewedBy(reviewerId);
        riskEvent.setReviewedAt(reviewedAt);
        riskEvent.setReviewNote(note);
        log.info("AUDIT event=RISK_EVENT_REVIEWED riskEventId={} {}->{} by={} note={}",
                eventId, from, to, reviewerId, sanitizeForLog(note));
        // 审计账本（尽力而为：AuditRecorder 内部吞掉失败，audit-service 暂不可用也不阻断审核）
        auditRecorder.record("RISK_EVENT_REVIEWED", "RISK_EVENT", eventId, riskEvent.getContestId(),
                from + "->" + to + (note == null || note.isBlank() ? "" : " note=" + note),
                null, operatorToken);
        writeReviewOutbox(riskEvent, from, to, reviewerId, reviewedAt);
        return toView(riskEvent);
    }

    private void writeOutbox(RiskEventEntity riskEvent, String traceId) {
        try {
            OutboxEventEntity event = new OutboxEventEntity();
            event.setId(UUID.randomUUID().toString());
            event.setEventId(UUID.randomUUID().toString());
            event.setEventType("lunar-risk-event-created");
            event.setAggregateId(riskEvent.getId());
            event.setSchemaVersion(1);
            event.setTraceId(traceId);
            // 用 LinkedHashMap 而不是 Map.of：problemId 允许为空（不是所有风险事件都绑定题目），
            // 而 Map.of 遇到 null 值直接抛 NPE。
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("riskEventId", riskEvent.getId());
            payload.put("contestId", riskEvent.getContestId());
            payload.put("studentId", riskEvent.getStudentId());
            payload.put("problemId", riskEvent.getProblemId());
            payload.put("eventType", riskEvent.getEventType());
            payload.put("severity", riskEvent.getSeverity());
            payload.put("occurredAt", riskEvent.getOccurredAt().toString());
            event.setPayload(objectMapper.writeValueAsString(payload));
            event.setOccurredAt(Instant.now());
            event.setCreatedAt(Instant.now());
            outboxMapper.insert(event);
        } catch (Exception exception) {
            throw new IllegalStateException("风险事件 Outbox 序列化失败", exception);
        }
    }

    /**
     * 审核流转事件（lunar-risk-event-reviewed）。
     *
     * 为什么必须单独发一条：风险事件只在**创建**时进 Outbox，若分析侧只消费 created，
     * 所有事件在统计里会永远停留在 OPEN——审核结论（CONFIRMED/DISMISSED）根本传不出去。
     *
     * 与状态更新在同一个事务里写入：审核成功而事件缺失会让分析侧长期漏掉一次流转；
     * 反过来（事件成功而审核回滚）则会凭空多出一条不存在的流转记录。两种都不能接受。
     */
    private void writeReviewOutbox(RiskEventEntity riskEvent, String fromStatus, String toStatus,
                                   String reviewerId, Instant reviewedAt) {
        try {
            OutboxEventEntity event = new OutboxEventEntity();
            event.setId(UUID.randomUUID().toString());
            event.setEventId(UUID.randomUUID().toString());
            event.setEventType("lunar-risk-event-reviewed");
            event.setAggregateId(riskEvent.getId());
            event.setSchemaVersion(1);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("riskEventId", riskEvent.getId());
            payload.put("contestId", riskEvent.getContestId());
            payload.put("studentId", riskEvent.getStudentId());
            payload.put("previousStatus", fromStatus);
            payload.put("newStatus", toStatus);
            payload.put("reviewedBy", reviewerId);
            payload.put("reviewedAt", reviewedAt.toString());
            event.setPayload(objectMapper.writeValueAsString(payload));
            event.setOccurredAt(Instant.now());
            event.setCreatedAt(Instant.now());
            outboxMapper.insert(event);
        } catch (Exception exception) {
            throw new IllegalStateException("风险审核 Outbox 序列化失败", exception);
        }
    }

    private RiskEventView toView(RiskEventEntity riskEvent) {
        return new RiskEventView(riskEvent.getId(), riskEvent.getContestId(), riskEvent.getStudentId(),
                riskEvent.getProblemId(), riskEvent.getEventType(), riskEvent.getSeverity(), riskEvent.getDetail(),
                riskEvent.getOccurredAt(), riskEvent.getReportedAt(), riskEvent.getReviewStatus(),
                riskEvent.getReviewedBy(), riskEvent.getReviewedAt(), riskEvent.getReviewNote());
    }
}
