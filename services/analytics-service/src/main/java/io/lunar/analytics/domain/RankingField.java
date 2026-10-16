package io.lunar.analytics.domain;

import io.lunar.analytics.domain.dto.ScoreAggregate;

import java.time.Instant;

/**
 * 榜单排序的候选字段。
 *
 * 每种字段自己处理方向与空值，而不是"取比较器再 reversed()"：reversed() 会把 null 的处置一并翻转
 * （{@code last_effective_at asc} 下"从未判决"的学生本该排在最后，翻转后会跑到最前）。
 * 这里的约定是**空值恒排最后，与方向无关**——"没有这个值"不等于"这个值最小或最大"。
 */
public enum RankingField {

    /** 总分：默认第一档，降序（分高者在前）。 */
    TOTAL_SCORE("total_score") {
        @Override
        public int compare(ScoreAggregate left, ScoreAggregate right, boolean descending) {
            return descending
                    ? Integer.compare(right.totalScore(), left.totalScore())
                    : Integer.compare(left.totalScore(), right.totalScore());
        }
    },

    /**
     * 最后一次判决时间。
     *
     * 升序的语义是"更早达到当前分数者在前"——这是"同等成绩下先做到的人更靠前"的常见口径，
     * 但它属于 docs/exam-process.md §10 的未确认项，因此方向可配。
     */
    LAST_EFFECTIVE_AT("last_effective_at") {
        @Override
        public int compare(ScoreAggregate left, ScoreAggregate right, boolean descending) {
            Instant leftValue = left.lastEffectiveAt();
            Instant rightValue = right.lastEffectiveAt();
            if (leftValue == null || rightValue == null) {
                return missingLast(leftValue == null, rightValue == null);
            }
            int ascending = leftValue.compareTo(rightValue);
            return descending ? -ascending : ascending;
        }
    },

    /** 解决题数：默认降序（解出更多题目者在前）。 */
    SOLVED_COUNT("solved_count") {
        @Override
        public int compare(ScoreAggregate left, ScoreAggregate right, boolean descending) {
            return descending
                    ? Integer.compare(right.solvedCount(), left.solvedCount())
                    : Integer.compare(left.solvedCount(), right.solvedCount());
        }
    },

    /**
     * 学生 ID：默认作为最后一档升序。
     *
     * 它必须出现在排序链里的意义不是"公平"，而是**稳定**：没有唯一字段兜底，
     * 同分学生在翻页时顺序由数据库决定，会出现某个学生被跳过或重复出现。
     */
    STUDENT_ID("student_id") {
        @Override
        public int compare(ScoreAggregate left, ScoreAggregate right, boolean descending) {
            int ascending = left.studentId().compareTo(right.studentId());
            return descending ? -ascending : ascending;
        }
    };

    private final String key;

    RankingField(String key) {
        this.key = key;
    }

    /** 比较两个聚合结果在该字段上的先后；{@code descending} 为 true 时把升序结论取反。 */
    public abstract int compare(ScoreAggregate left, ScoreAggregate right, boolean descending);

    /** 配置里使用的字段名（下划线命名，与列名一致，便于对照 SQL）。 */
    public String key() {
        return key;
    }

    /** 按配置里的字段名解析；未知名字返回 null，由调用方决定是忽略还是启动即失败。 */
    public static RankingField parse(String key) {
        for (RankingField field : values()) {
            if (field.key.equalsIgnoreCase(key)) {
                return field;
            }
        }
        return null;
    }

    /** 空值处理：两侧都缺视为相等，否则缺的那侧恒排在后面。 */
    private static int missingLast(boolean leftMissing, boolean rightMissing) {
        if (leftMissing && rightMissing) {
            return 0;
        }
        return leftMissing ? 1 : -1;
    }
}
