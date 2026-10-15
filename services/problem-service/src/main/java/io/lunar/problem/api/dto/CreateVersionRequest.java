package io.lunar.problem.api.dto;


import io.lunar.problem.domain.dto.TestcasePayload;
import java.util.List;

/** 创建新版本请求体：baseVersionId 为空则基于当前发布版本，字段缺省沿用基线版本。 */
public record CreateVersionRequest(String baseVersionId, String title, String statement,
                                   String sampleInput, String sampleOutput,
                                   Integer timeLimitMs, Integer memoryLimitKb, Integer outputLimitKb,
                                   String allowedLanguages, List<TestcasePayload> testcases) {}
