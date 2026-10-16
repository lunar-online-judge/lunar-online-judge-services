package io.lunar.ai.infra.dto;

/**
 * 提交详情（submission-service 的服务间契约，调用方按字段名取值）。
 *
 * 为什么另建这个 DTO 而不是复用 submission 的 {@code SubmissionView}：后者是**考生/教师视角**的视图，
 * 刻意不含源码（源码读取是敏感动作）；AI 评审需要源码，因此由服务间端点单独给出，
 * 并且只认服务身份——把源码塞进公开视图是绝对不能做的。
 */
public record SubmissionDetail(String submissionId,
                               String contestId,
                               String problemId,
                               String problemVersionId,
                               String studentId,
                               String language,
                               String status,
                               Integer score,
                               Integer passedCases,
                               Integer totalCases,
                               String sourceCode) {
}
