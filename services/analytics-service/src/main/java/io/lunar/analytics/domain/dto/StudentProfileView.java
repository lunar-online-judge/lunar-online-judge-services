package io.lunar.analytics.domain.dto;

import java.time.Instant;
import java.util.List;

/**
 * 单个学生的学情视图（按需聚合，不落物化表）。
 *
 * 为什么实时聚合就够：一场考试里单个学生的提交量是有限且可控的（频率限制封顶），
 * 而聚合的目标是"做题过程质量"这种没有标准答案的画像——物化它反而要额外定义失效策略，
 * 一处通知漏掉就会长期展示过期结论。
 */
public record StudentProfileView(String studentId,
                                 String studentNo,
                                 String displayName,
                                 String enrollmentStatus,
                                 Integer rank,
                                 Integer totalScore,
                                 Integer solvedCount,
                                 Instant lastEffectiveAt,
                                 List<LabeledCount> verdictCounts,
                                 List<LabeledCount> languageCounts,
                                 List<ProblemResult> problems,
                                 List<SubmissionTimelineEntry> recentSubmissions) {
}
