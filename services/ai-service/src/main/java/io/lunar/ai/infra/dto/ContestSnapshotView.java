package io.lunar.ai.infra.dto;

/**
 * 考试快照的**调用方视图**（contest-service 的 {@code /api/v1/internal/contests/{id}/snapshot} 契约）。
 *
 * 只声明范围判断需要的三个字段：Jackson 默认忽略未知字段，上游返回完整名单与题目清单也不会出错。
 * 刻意不把候选人名单抄进来——AI 侧只需要"这场考试归谁"，多拿一份全员名单只是多一处数据面。
 */
public record ContestSnapshotView(String contestId, String name, String status, String ownerTeacherId) {
}
