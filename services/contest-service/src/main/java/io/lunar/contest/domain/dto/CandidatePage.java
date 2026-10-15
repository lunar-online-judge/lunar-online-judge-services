package io.lunar.contest.domain.dto;

import java.util.List;

/** 分页列表响应：items 为当前页名单。 */
public record CandidatePage(List<CandidateView> items, long total, long page, long pageSize) {}
