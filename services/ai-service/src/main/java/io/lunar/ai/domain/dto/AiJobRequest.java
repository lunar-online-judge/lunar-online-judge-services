package io.lunar.ai.domain.dto;

import io.lunar.ai.domain.AiJobType;

/**
 * 入队请求（领域层入参）。
 *
 * 引用字段（contest/problem/submission/student）按作业类型取用，不必全部给齐：
 * 代码评审要 submissionId，风险研判要 contestId+studentId，查重要 contestId+problemId，
 * 出题只要 inputPayload。缺必需字段由对应处理器抛 {@code AI_INVALID_REQUEST}，
 * 而不是把校验逻辑散落在 controller 里（不同作业类型的必填项本来就不同）。
 *
 * {@code requestedBy}/{@code requestedRole} 由 controller 从**已验证令牌**填入，绝不从请求体取（BE-01）。
 */
public record AiJobRequest(AiJobType jobType,
                           String contestId,
                           String problemId,
                           String submissionId,
                           String studentId,
                           String inputPayload,
                           String requestedBy,
                           String requestedRole) {
}
