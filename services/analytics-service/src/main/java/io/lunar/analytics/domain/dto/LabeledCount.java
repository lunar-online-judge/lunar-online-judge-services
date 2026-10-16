package io.lunar.analytics.domain.dto;

/**
 * 单一维度的计数（严重度/事件类型/审核状态/verdict/语言都用它）。
 *
 * 共用同一个载体是刻意的：这些分布的形状完全一致，各自建一个 record 只会让"新增一个维度"变成
 * 新增三个文件；{@code label} 的取值域由调用方的 group by 决定。
 */
public record LabeledCount(String label, int itemCount) {
}
