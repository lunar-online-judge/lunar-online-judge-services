package io.lunar.contest.api.dto;

import java.util.List;

/** 分页列表响应：items 为当前页竞赛。 */
public record ContestListResponse(List<ContestResponse> items, long total, long page, long pageSize) {}
