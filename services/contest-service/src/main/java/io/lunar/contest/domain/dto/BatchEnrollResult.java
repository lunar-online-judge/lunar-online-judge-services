package io.lunar.contest.domain.dto;

import java.util.List;

/** 批量加入名单的汇总结果。 */
public record BatchEnrollResult(int added, int skipped, int failed, List<BatchEnrollItem> items) {
}
