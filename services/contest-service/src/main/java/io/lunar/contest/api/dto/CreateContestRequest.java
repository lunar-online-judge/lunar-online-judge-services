package io.lunar.contest.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/** 创建竞赛请求体：startAt / endAt 为考试时间窗口。 */
public record CreateContestRequest(@NotBlank String name, String description,
                                   Instant startAt, Instant endAt) {}
