package io.lunar.submission.domain.dto;

/**
 * 服务间提交摘要（**含源码**，供 ai-service 的语义查重与行为特征分析批量取数）。
 *
 * 与单条详情分开的理由：批量场景的调用方只关心"同一场考试同一道题的一批提交"，
 * 而批量接口最容易变成数据外泄面，因此**上限写死在服务端**（调用方传再大也会被夹取）。
 */
public record InternalSubmissionSummary(String submissionId,
                                        String studentId,
                                        String language,
                                        String status,
                                        String sourceHash,
                                        String sourceCode) {
}
