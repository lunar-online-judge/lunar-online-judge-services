package io.lunar.analytics.domain.dto;

import java.util.List;

/**
 * 风险汇总视图：标量计数 + 三个分布 + 热力图。
 *
 * 全部来自 analytics_schema.risk_event_facts 的按需聚合——风险事件的量级远小于提交，
 * 且审核状态会被反复流转，物化它就得跟着状态机同步失效，收益不抵复杂度。
 *
 * 原先还有一个 {@code aiFindingCount}（AI 结论条数）：按简化决定，AI findings 读模型已下线，
 * 该字段随之移除——读模型不在了却继续回显一个恒为 0 的计数，比不显示更容易误导。
 */
public record RiskSummaryView(int totalEvents,
                              int pendingReview,
                              int confirmedCount,
                              int dismissedCount,
                              int studentsWithRisk,
                              List<LabeledCount> bySeverity,
                              List<LabeledCount> byEventType,
                              List<LabeledCount> byReviewStatus,
                              List<RiskHeatCell> heatmap) {
}
