package io.lunar.submission.domain.dto;

import java.time.Instant;

/** 提交列表行：不含源码，供教师/管理员查询与跳转详情。 */
public record SubmissionSummary(String submissionId, String contestId, String problemId,
                                String studentId, String language, String status,
                                Integer score, Integer passedCases, Integer totalCases,
                                Instant createdAt, Instant updatedAt) {
}
