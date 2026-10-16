package io.lunar.analytics.domain.dto;

import java.time.Instant;

/**
 * 风险事件按学生×时间窗的聚合计数（热力图的一个格子）。
 *
 * {@code windowStart} 是桶的起点（桶宽由 {@code analytics.risk.heatmap-bucket-minutes} 决定），
 * 返回起点而非"第几桶"：跨服务/跨前端计算桶边界容易各算一份，带上真实时刻就不必再推导。
 */
public record RiskHeatCell(String studentId, Instant windowStart, int itemCount) {
}
