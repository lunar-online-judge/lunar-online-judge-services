package io.lunar.ai.domain.dto;

/**
 * 出题草稿里的单个测试点。
 *
 * 字段名与 problem-service 的测试点约定（name / input / expectedOutput）保持一致，
 * 教师确认后可以直接照抄进题库，不需要做字段改名的心智转换。
 */
public record ProblemDraftTestcase(String name, String input, String expectedOutput) {
}
