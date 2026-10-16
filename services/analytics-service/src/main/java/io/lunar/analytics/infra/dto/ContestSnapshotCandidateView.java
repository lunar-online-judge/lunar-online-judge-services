package io.lunar.analytics.infra.dto;

/**
 * 考试快照里的考生项。
 *
 * 字段全部来自报名时的快照列（contest_candidates 的 *_snapshot），因此学生后来改名不会改写历史名单的呈现。
 */
public record ContestSnapshotCandidateView(String studentId,
                                           String studentNo,
                                           String username,
                                           String displayName,
                                           String enrollmentStatus) {
}
