package io.lunar.contest.domain.dto;

import java.time.Instant;
import java.util.List;

/**
 * 考试快照（docs/api.md 端点总表的服务间接口）：一次给出分析型消费者需要的全部"考试侧参照数据"。
 *
 * 为什么要有这个接口：仓库明令"服务禁止跨 schema 查库"（docs/services.md），
 * 而 analytics-service 的读模型必须知道这场考试叫什么、谁负责、有哪些考生、锁定了哪些题目版本；
 * 已有的 {@code /internal/contests/{id}/session} 是**考生令牌**语义（判定 canSubmit），分析服务无法使用。
 */
public record ContestSnapshot(String contestId,
                              String name,
                              String status,
                              Instant startAt,
                              Instant endAt,
                              String ownerTeacherId,
                              List<ContestSnapshotCandidate> candidates,
                              List<ContestSnapshotProblem> problems) {
}
