package io.lunar.contest.domain.dto;

/**
 * 考试快照里的考生项（服务间参照数据）：字段全部来自报名时的**快照列**
 * （contest_candidates 的 *_snapshot），因此学生后来改名不会改写历史名单的呈现。
 *
 * enrollmentStatus 保留原始值（ENROLLED / REMOVED 等）而不在服务端过滤：
 * 快照是参照数据的缓存，过滤交给使用方，否则"某学生被移出名单"在分析侧无法解释。
 */
public record ContestSnapshotCandidate(String studentId,
                                       String studentNo,
                                       String username,
                                       String displayName,
                                       String enrollmentStatus) {
}
