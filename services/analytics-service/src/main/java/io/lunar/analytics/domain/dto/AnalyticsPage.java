package io.lunar.analytics.domain.dto;

import java.util.List;

/** 分页结果（沿用仓库约定：page 从 1 起，响应含总数与当前页）。 */
public record AnalyticsPage<T>(List<T> items, long total, long page, long pageSize) {
}
