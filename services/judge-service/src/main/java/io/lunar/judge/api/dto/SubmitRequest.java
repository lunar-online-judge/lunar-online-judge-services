package io.lunar.judge.api.dto;

/** 提交请求体：字段名即 API 契约，禁改。 */
public record SubmitRequest(String submissionId, String language, String sourceCode,
                            long cpuTimeLimitMs, long memoryLimitKb) {}
