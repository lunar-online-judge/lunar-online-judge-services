package io.lunar.analytics.domain.dto;

import java.time.Instant;

/**
 * 题目维度的学情统计。
 *
 * {@code sortOrder} 与 {@code problemVersionId} 来自参照数据（contest_problem_refs），
 * 因此题目的展示顺序与"当时锁定的版本"都能还原；{@code averageElapsedSeconds} 为 null 表示
 * 该题没有任何一条提交带过 submitted_at（列可空，不能假装是 0 秒）。
 */
public record ProblemStats(String problemId,
                           String problemVersionId,
                           Integer sortOrder,
                           int attempts,
                           int acceptedCount,
                           int distinctStudents,
                           Double averageScore,
                           Double averageElapsedSeconds,
                           Instant firstAttemptAt,
                           Instant lastAttemptAt) {
}
