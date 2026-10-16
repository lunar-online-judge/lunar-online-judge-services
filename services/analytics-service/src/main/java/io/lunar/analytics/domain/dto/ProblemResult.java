package io.lunar.analytics.domain.dto;

import java.time.Instant;

/**
 * 某学生在一道题上的结果（学情视图的一行）。
 *
 * {@code problemId} 取的是"题目键"：正常提交就是 problem_id，而判决事件没带 problemId 的提交
 * 退化为以 submission_id 为键，因此它不一定是个题目——这一行会以"未归属题目"的形式出现，
 * 而不是被悄悄并进某一题里（合并会让统计凭空多出/少掉尝试次数）。
 */
public record ProblemResult(String problemId,
                            Integer bestScore,
                            int attempts,
                            String lastVerdict,
                            Instant lastJudgedAt) {
}
