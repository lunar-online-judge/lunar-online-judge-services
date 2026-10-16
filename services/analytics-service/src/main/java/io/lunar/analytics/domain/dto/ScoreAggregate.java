package io.lunar.analytics.domain.dto;

import java.time.Instant;

/**
 * 单个学生在某场考试里的分数聚合结果（重算的输入）。
 *
 * {@code totalScore} 是各题最高分之和；{@code solvedCount} 统计的是"存在过 ACCEPTED 判决的题目数"，
 * 而不是"最高分大于 0 的题目数"——部分分方案下后者会把只拿到部分分的题也算作已解决。
 */
public record ScoreAggregate(String studentId, int totalScore, int solvedCount, Instant lastEffectiveAt) {
}
