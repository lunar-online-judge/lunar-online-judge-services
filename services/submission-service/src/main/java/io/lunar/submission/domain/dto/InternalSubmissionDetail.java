package io.lunar.submission.domain.dto;

/**
 * 提交详情（**服务间**读模型，含源码）。
 *
 * 为什么与 {@code SubmissionView} 分开：那是考生/教师视角的视图，刻意不含源码——
 * 源码只在"按需且经过授权"的场景读取。AI 代码评审就是这样一个场景，因此单独给出这个视图，
 * 并且端点**只认服务身份**（`roles=["SERVICE"]`），不靠"内网 + 任意已认证令牌"保护源码。
 */
public record InternalSubmissionDetail(String submissionId,
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
