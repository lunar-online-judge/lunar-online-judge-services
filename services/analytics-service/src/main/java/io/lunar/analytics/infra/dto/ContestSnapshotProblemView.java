package io.lunar.analytics.infra.dto;

/**
 * 考试快照里的题目项：只含"这场考试锁定了哪个题目的哪个版本"与出题顺序。
 *
 * 题面与限制属于 problem-service，分析侧只做统计，不需要它们。
 */
public record ContestSnapshotProblemView(String problemId, String problemVersionId, Integer sortOrder) {
}
