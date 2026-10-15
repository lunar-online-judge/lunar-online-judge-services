package io.lunar.submission.domain.dto;

import java.time.Instant;

/** 提交视图：status 为提交状态机取值，score/passedCases/totalCases 在判题回写前为空。 */
public record SubmissionView(String submissionId, String contestId, String problemId, String language,
                             String status, Integer score, Integer passedCases, Integer totalCases,
                             Instant createdAt) {}
