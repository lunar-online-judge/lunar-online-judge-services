package io.lunar.contest.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 把题目加入考试并锁定版本（BE-04）：必须显式给出 problemVersionId。 */
public record LockProblemRequest(@NotBlank String problemId, @NotBlank String problemVersionId) {
}
