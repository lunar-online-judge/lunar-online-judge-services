package io.lunar.contest.domain.dto;

/** 批量加入名单的单条结果：ENROLLED / SKIPPED / FAILED。 */
public record BatchEnrollItem(String studentId, String username, String status, String message) {
}
