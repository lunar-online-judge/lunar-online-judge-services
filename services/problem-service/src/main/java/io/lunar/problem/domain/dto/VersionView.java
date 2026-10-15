package io.lunar.problem.domain.dto;

import java.time.Instant;
import java.util.List;

/** 版本视图：题面/样例/限制/语言；考生只看当前发布版本，绝不包含测试点内容。 */
public record VersionView(String versionId, String problemId, int versionNo, String title, String statement,
                          String sampleInput, String sampleOutput, int timeLimitMs, int memoryLimitKb,
                          int outputLimitKb, List<String> allowedLanguages, String status,
                          String createdBy, Instant createdAt, Instant publishedAt) {}
