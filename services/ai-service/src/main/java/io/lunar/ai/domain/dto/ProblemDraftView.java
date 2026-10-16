package io.lunar.ai.domain.dto;

import io.lunar.ai.infra.ProblemDraftEntity;

import java.time.Instant;

/** 出题草稿视图（对外读模型）。 */
public record ProblemDraftView(String draftId,
                               String jobId,
                               String contestId,
                               String title,
                               String statement,
                               String sampleInput,
                               String sampleOutput,
                               String suggestedTestcasesJson,
                               String knowledgeTagsJson,
                               String difficulty,
                               String languageHint,
                               String reviewStatus,
                               String reviewedBy,
                               Instant reviewedAt,
                               Instant createdAt) {

    public static ProblemDraftView of(ProblemDraftEntity draft) {
        return new ProblemDraftView(draft.getId(), draft.getJobId(), draft.getContestId(), draft.getTitle(),
                draft.getStatement(), draft.getSampleInput(), draft.getSampleOutput(),
                draft.getSuggestedTestcases(), draft.getKnowledgeTags(), draft.getDifficulty(),
                draft.getLanguageHint(), draft.getReviewStatus(), draft.getReviewedBy(), draft.getReviewedAt(),
                draft.getCreatedAt());
    }
}
