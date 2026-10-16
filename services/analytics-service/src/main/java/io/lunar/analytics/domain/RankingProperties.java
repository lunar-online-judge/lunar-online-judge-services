package io.lunar.analytics.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 榜单排序配置（{@code analytics.ranking.*}）。
 *
 * 为什么 tie-break 必须可配：docs/exam-process.md §10 明确把"同分怎么排"列为**尚未确认**的产品决策，
 * 而它一旦写死在 SQL 里，改口径就要发版。默认值取最保守的一组（分数 → 先达到 → 解出题数 → 学生 ID）。
 */
@ConfigurationProperties("analytics.ranking")
public class RankingProperties {

    /**
     * 排序档位，按优先级排列，写法 {@code "<字段>:<asc|desc>"}。
     *
     * 默认值：{@code total_score:desc}（分高者在前）→ {@code last_effective_at:asc}（先达到该分数者在前）
     * → {@code solved_count:desc}（解出题数多者在前）→ {@code student_id:asc}（唯一键兜底，保证稳定）。
     * **前三档的顺序与方向均待产品确认**；最后一档不是产品口径，是分页稳定性的硬要求，配置里缺了会被自动补上。
     */
    private List<String> tieBreak = List.of(
            "total_score:desc",
            "last_effective_at:asc",
            "solved_count:desc",
            "student_id:asc");

    public List<String> getTieBreak() {
        return tieBreak;
    }

    public void setTieBreak(List<String> tieBreak) {
        this.tieBreak = tieBreak;
    }
}
