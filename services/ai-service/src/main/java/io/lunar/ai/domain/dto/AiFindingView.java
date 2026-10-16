package io.lunar.ai.domain.dto;

import io.lunar.ai.infra.AiFindingEntity;

import java.time.Instant;

/**
 * 结论视图（对外读模型）。
 *
 * {@code score} 的契约必须说清楚：它是**可疑度 0..100，仅用于排序与展示**，
 * 任何调用方都不得用它驱动自动处置（仓库铁律：单一信号不能自动判定作弊）。
 */
public record AiFindingView(String findingId,
                           String jobId,
                           String findingType,
                           String contestId,
                           String problemId,
                           String submissionId,
                           String studentId,
                           String severity,
                           Integer score,
                           String summary,
                           String detailJson,
                           String model,
                           String promptVersion,
                           Instant createdAt) {

    public static AiFindingView of(AiFindingEntity finding) {
        return new AiFindingView(finding.getId(), finding.getJobId(), finding.getFindingType(),
                finding.getContestId(), finding.getProblemId(), finding.getSubmissionId(), finding.getStudentId(),
                finding.getSeverity(), finding.getScore(), finding.getSummary(), finding.getDetail(),
                finding.getModel(), finding.getPromptVersion(), finding.getCreatedAt());
    }
}
