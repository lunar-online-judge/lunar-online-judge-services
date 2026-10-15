package io.lunar.anticheat.domain.dto;

import java.time.Instant;

/** 风险事件视图（监考/管理端）：含审核状态与审核人、审核时间。 */
public record RiskEventView(String eventId, String contestId, String studentId, String problemId,
                            String eventType, String severity, String detail, Instant occurredAt,
                            Instant reportedAt, String reviewStatus, String reviewedBy,
                            Instant reviewedAt, String reviewNote) {}
