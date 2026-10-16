package io.lunar.analytics.domain.dto;

import java.time.Instant;

/**
 * 榜单的一行：名次 + 分数聚合 + 参照数据里的学号/姓名。
 *
 * 学号与姓名可能为 null（参照数据尚未刷新到该考生），这不影响该行的成绩数据本身。
 */
public record ScoreboardRow(Integer rank,
                            String studentId,
                            String studentNo,
                            String displayName,
                            int totalScore,
                            int solvedCount,
                            Instant lastEffectiveAt,
                            Instant computedAt) {
}
