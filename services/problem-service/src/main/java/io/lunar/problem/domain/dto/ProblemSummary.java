package io.lunar.problem.domain.dto;

import java.time.Instant;

/** 管理端列表行：不含题面与测试点，避免列表接口拉大字段。 */
public record ProblemSummary(String problemId, String title, String currentVersionId, Instant createdAt) {}
