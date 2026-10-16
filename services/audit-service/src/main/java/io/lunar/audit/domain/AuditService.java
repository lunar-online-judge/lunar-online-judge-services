package io.lunar.audit.domain;

import io.lunar.audit.domain.dto.AuditPage;
import io.lunar.audit.domain.dto.AuditRecordView;
import io.lunar.audit.infra.AuditLogEntity;
import io.lunar.audit.infra.AuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计账本（docs/services.md audit-service 节）：追加式，仅写入与查询。
 * 记录结构：动作（大写下划线自由字符串）+ 操作者（人或 SYSTEM）+ 目标（类型/ID）+ 竞赛 + 明细。
 * 约束：不记录密码及任何敏感明文；本服务不提供更新/删除能力。
 */
@Service
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    public AuditService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 写入审计记录（服务间接口调用）。
     * actor 为空时视为系统动作；serviceId 非空表示这是服务身份（SERVICE 令牌）发起的动作，
     * 与用户操作者分开保存（BE-07）。
     */
    public AuditRecordView record(String action, String actorId, String actorRole, String serviceId,
                                  String targetType, String targetId, String contestId,
                                  String detail, String traceId) {
        return record(action, actorId, actorRole, serviceId, targetType, targetId, contestId, detail, traceId,
                null, null);
    }

    /**
     * 写入审计记录（带来源 {@code eventId} 与动作发生时间，异步消费端用）：账本上有唯一索引
     * {@code uk_audit_logs_event_id}，重复投递会命中唯一冲突——"至少一次"投递因此收敛成"恰好一条"（BE-07）。
     */
    public AuditRecordView record(String action, String actorId, String actorRole, String serviceId,
                                  String targetType, String targetId, String contestId,
                                  String detail, String traceId, String eventId, Instant originalOccurredAt) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setId(UUID.randomUUID().toString());
        auditLog.setAction(action);
        auditLog.setActorId(actorId);
        auditLog.setActorRole(actorRole == null ? (actorId == null ? "SYSTEM" : null) : actorRole);
        auditLog.setServiceId(serviceId);
        auditLog.setTargetType(targetType);
        auditLog.setTargetId(targetId);
        auditLog.setContestId(contestId);
        auditLog.setDetail(detail);
        auditLog.setTraceId(traceId);
        auditLog.setEventId(eventId);
        auditLog.setOriginalOccurredAt(originalOccurredAt);
        auditLog.setOccurredAt(Instant.now());
        auditLog.setCreatedAt(Instant.now());
        auditLogMapper.insert(auditLog);
        return toView(auditLog);
    }

    /** 分页查询：按时间倒序，可选动作/操作者/竞赛过滤。 */
    public AuditPage query(String action, String actorId, String contestId, long page, long pageSize) {
        LambdaQueryWrapper<AuditLogEntity> query = new LambdaQueryWrapper<AuditLogEntity>()
                .orderByDesc(AuditLogEntity::getOccurredAt);
        if (action != null && !action.isBlank()) {
            query.eq(AuditLogEntity::getAction, action);
        }
        if (actorId != null && !actorId.isBlank()) {
            query.eq(AuditLogEntity::getActorId, actorId);
        }
        if (contestId != null && !contestId.isBlank()) {
            query.eq(AuditLogEntity::getContestId, contestId);
        }
        Page<AuditLogEntity> result = auditLogMapper.selectPage(new Page<>(page, pageSize), query);
        return new AuditPage(result.getRecords().stream().map(this::toView).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    private AuditRecordView toView(AuditLogEntity auditLog) {
        return new AuditRecordView(auditLog.getId(), auditLog.getAction(), auditLog.getActorId(),
                auditLog.getActorRole(), auditLog.getServiceId(), auditLog.getTargetType(), auditLog.getTargetId(),
                auditLog.getContestId(), auditLog.getDetail(), auditLog.getTraceId(),
                auditLog.getOccurredAt());
    }
}
