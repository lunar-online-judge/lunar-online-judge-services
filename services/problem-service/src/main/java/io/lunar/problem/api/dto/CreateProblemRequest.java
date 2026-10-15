package io.lunar.problem.api.dto;

import io.lunar.problem.domain.dto.TestcasePayload;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 创建题目请求体：testcases 为随题创建的测试点。 */
public record CreateProblemRequest(@NotBlank String title, @NotBlank String statement,
                                   String sampleInput, String sampleOutput,
                                   Integer timeLimitMs, Integer memoryLimitKb, Integer outputLimitKb,
                                   String allowedLanguages, List<TestcasePayload> testcases) {}
