package io.lunar.contest.domain.dto;

/**
 * CSV 名单中的一行（docs/exam-process.md §9：studentNo,username,displayName,className,initialPassword）。
 * 列缺失时字段为 null，由导入流程按行返回"缺少必填字段"，而不是让整批导入失败。
 */
public record RosterEntry(int lineNumber, String studentNo, String username,
                          String displayName, String className, String initialPassword) {
}
