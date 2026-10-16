package io.lunar.contest.domain.dto;

/**
 * 逐行导入结果（docs/exam-process.md §9）：status 为 ENROLLED（该行已进入名单）或 FAILED。
 * 失败时带稳定错误码（域异常码，如 CONTEST_STUDENT_ALREADY_ENROLLED）与可读原因；
 * 未分类失败（上游不可用等）errorCode 为 null，原因不泄漏内部细节。
 */
public record RosterRowResult(int lineNumber, String studentNo, String status, String studentId,
                              String errorCode, String message) {
}
