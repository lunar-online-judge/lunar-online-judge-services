package io.lunar.contest.api.dto;

import io.lunar.contest.infra.ContestEntity;

import java.time.Instant;

/** 竞赛视图：字段与 docs/api.md 的竞赛契约一致。 */
public record ContestResponse(String id, String name, String description, String status,
                              Instant startAt, Instant endAt, String createdBy,
                              Instant createdAt, Instant updatedAt) {

    public static ContestResponse of(ContestEntity contest) {
        return new ContestResponse(contest.getId(), contest.getName(), contest.getDescription(),
                contest.getStatus(), contest.getStartAt(), contest.getEndAt(), contest.getCreatedBy(),
                contest.getCreatedAt(), contest.getUpdatedAt());
    }
}
