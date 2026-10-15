package io.lunar.problem.domain.dto;

import java.util.List;

/** 分页列表响应：items 为当前页题目。 */
public record ProblemPage(List<ProblemSummary> items, long total, long page, long pageSize) {}
