package io.lunar.submission.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 提交请求体：幂等键走 Idempotency-Key 请求头，不在请求体内。 */
public record CreateSubmissionRequest(@NotBlank String contestId, @NotBlank String problemId,
                                      @NotBlank String language, @NotBlank String sourceCode) {}
