package io.lunar.contest.domain.dto;

import io.lunar.contest.infra.ContestEntity;

import java.util.List;

/** 管理端列表分页：items 为当前页竞赛。 */
public record ContestPage(List<ContestEntity> items, long total, long page, long pageSize) {}
