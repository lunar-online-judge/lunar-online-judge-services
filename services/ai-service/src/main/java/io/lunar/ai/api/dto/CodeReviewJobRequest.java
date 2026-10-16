package io.lunar.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 请求代码评审。
 *
 * 必须给 {@code contestId}：AI 侧要靠它取考试归属做资源范围判断（BE-01），
 * 否则只能退化成 ADMIN-only。{@code submissionId} 是评审对象。
 */
public record CodeReviewJobRequest(@NotBlank String contestId, @NotBlank String submissionId) {
}
