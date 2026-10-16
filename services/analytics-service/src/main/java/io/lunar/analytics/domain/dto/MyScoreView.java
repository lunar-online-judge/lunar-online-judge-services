package io.lunar.analytics.domain.dto;

import java.time.Instant;

/**
 * 考生看自己的那一行成绩。
 *
 * 显式带 {@code visibility} 而不是"返回空就代表不可见"：可见范围是可配的
 * （{@code analytics.score-visibility}，docs/exam-process.md §10-6 未确认），
 * 前端必须能区分"你无权看"与"你还没提交过"——后者只是 {@code computed=false}。
 */
public record MyScoreView(String visibility,
                          boolean computed,
                          Integer rank,
                          Integer totalScore,
                          Integer solvedCount,
                          Instant lastEffectiveAt,
                          Instant computedAt) {
}
