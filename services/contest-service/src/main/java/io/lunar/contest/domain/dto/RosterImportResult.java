package io.lunar.contest.domain.dto;

import java.util.List;

/**
 * CSV 批量导入结果（docs/exam-process.md §9）：部分行失败不回滚已成功行，逐行返回成功或失败原因。
 *
 * 幂等由账号唯一性（学号/用户名）与名单唯一约束（竞赛+学生）保证：重复导入不会产生重复数据，
 * 已在名单中的行按 CONTEST_STUDENT_ALREADY_ENROLLED 逐行报回，调用方可据此视为"已存在"。
 */
public record RosterImportResult(int total, int succeeded, int failed, List<RosterRowResult> items) {
}
