package io.lunar.audit.domain.dto;

import java.time.Instant;

/** 审计记录视图：追加式账本的一行，不含密码及任何敏感明文。 */
public record AuditRecordView(String id, String action, String actorId, String actorRole,
                              String targetType, String targetId, String contestId,
                              String detail, String traceId, Instant occurredAt) {}
