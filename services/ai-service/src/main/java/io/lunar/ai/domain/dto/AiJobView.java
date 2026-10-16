package io.lunar.ai.domain.dto;

import io.lunar.ai.infra.AiJobEntity;

import java.time.Instant;

/** 作业视图（对外读模型）：不暴露 input_payload 等输入细节，避免把学生源码经作业接口二次扩散。 */
public record AiJobView(String jobId,
                        String jobType,
                        String status,
                        String contestId,
                        String problemId,
                        String submissionId,
                        String studentId,
                        String requestedBy,
                        String model,
                        String promptVersion,
                        String errorCode,
                        String statusDetail,
                        Integer attempts,
                        Instant createdAt,
                        Instant startedAt,
                        Instant finishedAt) {

    public static AiJobView of(AiJobEntity job) {
        return new AiJobView(job.getId(), job.getJobType(), job.getStatus(), job.getContestId(), job.getProblemId(),
                job.getSubmissionId(), job.getStudentId(), job.getRequestedBy(), job.getModel(),
                job.getPromptVersion(), job.getErrorCode(), job.getStatusDetail(), job.getAttempts(),
                job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt());
    }
}
