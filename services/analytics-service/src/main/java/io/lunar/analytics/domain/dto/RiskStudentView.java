package io.lunar.analytics.domain.dto;

import java.time.Instant;
/**
 * 风险学生列表的一行（只含风险事件统计：AI 证据读模型已下线，不再合并模型结论）。
 */
public record RiskStudentView(String studentId,
                              String studentNo,
                              String displayName,
                              int riskScore,
                              int eventCount,
                              int highSeverityCount,
                              int confirmedCount,
                              int pendingCount,
                              Instant latestOccurredAt) {
}
