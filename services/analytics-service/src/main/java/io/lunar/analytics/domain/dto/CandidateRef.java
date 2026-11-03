package io.lunar.analytics.domain.dto;

/**
 * 参照数据里的一个考生（学号/姓名）。
 *
 * 成绩聚合只产出 student_id，学号与姓名来自 contest 的参照数据（`contest_candidate_refs`）。
 * 两者可能缺失（参照数据还没刷新到该考生），此时学号/姓名为 null——这不影响成绩数据本身。
 */
public record CandidateRef(String studentId, String studentNo, String displayName) {
}
