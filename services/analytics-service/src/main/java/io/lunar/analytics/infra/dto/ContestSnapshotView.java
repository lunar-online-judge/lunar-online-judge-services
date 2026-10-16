package io.lunar.analytics.infra.dto;

import java.time.Instant;
import java.util.List;

/**
 * 考试快照的**调用方视图**（contest-service 的 {@code /api/v1/internal/contests/{id}/snapshot} 契约）。
 *
 * 与 ai-service 那份精简视图的差别：AI 只判"这场考试归谁"，而分析侧要把它当作**参照数据的唯一来源**——
 * 竞赛名/时间窗/出题顺序/考生名册都要从这一次调用里拿全，否则一个考试要打三次上游。
 * 名单刻意保留 {@code enrollmentStatus}（含 REMOVED）：是否过滤是读模型的口径问题，缓存层不该替它决定。
 */
public record ContestSnapshotView(String contestId,
                                  String name,
                                  String status,
                                  Instant startAt,
                                  Instant endAt,
                                  String ownerTeacherId,
                                  List<ContestSnapshotCandidateView> candidates,
                                  List<ContestSnapshotProblemView> problems) {
}
