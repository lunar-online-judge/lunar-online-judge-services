package io.lunar.submission.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 判题结果回写请求体（服务间）：verdict 取值见 JudgeVerdict 词表。 */
public record JudgmentRequest(@NotBlank String verdict, Integer score,
                              Integer passedCases, Integer totalCases) {}
