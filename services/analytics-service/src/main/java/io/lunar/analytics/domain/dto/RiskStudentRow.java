package io.lunar.analytics.domain.dto;

import java.time.Instant;

/**
 * 风险学生列表的一行（未合并 AI 证据）。
 *
 * {@code riskScore} 由 SQL 按 {@code analytics.risk.weight-*} 加权算出——公式在
 * RiskEventFactMapper 里唯一定义（排序必须发生在数据库里才能配合分页，
 * 若改成 Java 侧排序就只能全量取回再切片）。
 */
public record RiskStudentRow(String studentId,
                             int riskScore,
                             int eventCount,
                             int highSeverityCount,
                             int confirmedCount,
                             int pendingCount,
                             Instant latestOccurredAt) {
}
