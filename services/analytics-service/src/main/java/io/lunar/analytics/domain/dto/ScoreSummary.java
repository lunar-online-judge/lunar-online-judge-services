package io.lunar.analytics.domain.dto;

/**
 * 全场总分的概览统计。
 *
 * 之所以 count/avg/max 一起返回：它们来自同一次聚合，分开查会让"直方图各档之和 != 总人数"这种
 * 不一致在两次查询之间出现（重算正在替换表时尤其明显）。
 */
public record ScoreSummary(int studentCount, Double averageScore, Integer maxScore) {
}
