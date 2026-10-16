package io.lunar.contest.domain.dto;

/**
 * 考试快照里的题目项（服务间参照数据）：只含"这场考试锁定了哪个题目的哪个版本"。
 *
 * 题面、限制与测试点属于 problem-service，分析服务要它们时走 problem 的服务间版本接口，
 * 不在这里冗余——那会让两个服务的元数据出现两份可能不一致的副本。
 */
public record ContestSnapshotProblem(String problemId, String problemVersionId, Integer sortOrder) {
}
