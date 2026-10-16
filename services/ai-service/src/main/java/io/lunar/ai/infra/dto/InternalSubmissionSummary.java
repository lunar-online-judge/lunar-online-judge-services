package io.lunar.ai.infra.dto;

/**
 * 提交摘要（submission-service 的服务间批量契约，**含源码**）。
 *
 * 批量接口比单条详情更敏感（一次可能带走几百份学生代码），因此服务端把条数上限写死在
 * 自己的 Service 层——这里的 {@code limit} 只是"我想要多少"，不是"我能拿多少"。
 */
public record InternalSubmissionSummary(String submissionId,
                                        String studentId,
                                        String language,
                                        String status,
                                        String sourceHash,
                                        String sourceCode) {
}
