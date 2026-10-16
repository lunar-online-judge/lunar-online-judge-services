package io.lunar.analytics.domain;

/**
 * 榜单排序链里的一档：字段 + 方向。
 *
 * 单独成类型（而不是在 RankingOrder 里内嵌 record）：解析配置与合成比较器是两件事，
 * 中间产物有了名字，"哪一档是方向可配的"才在类型上可见。
 */
public record ConfiguredField(RankingField field, boolean descending) {
}
