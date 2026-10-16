package io.lunar.analytics.domain.dto;

import java.time.Instant;

/**
 * 风险汇总的标量计数（一次查询取全）。
 *
 * {@code pendingReview} 把 OPEN 与 ACKNOWLEDGED 合并统计：监考视角关心的是"还有多少没结论"，
 * 而 ACKNOWLEDGED 只是"已看到、未处置"，分成两个指标会让待办数被看小。
 */
public record RiskSummaryCounts(int totalEvents,
                                int pendingReview,
                                int confirmedCount,
                                int dismissedCount,
                                int studentsWithRisk) {
}
