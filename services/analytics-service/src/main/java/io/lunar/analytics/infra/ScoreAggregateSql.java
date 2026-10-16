package io.lunar.analytics.infra;

/**
 * 成绩聚合的共享 SQL 片段。
 *
 * 为什么抽成常量而不是每个查询各写一遍：'每题取该生最高分、总分=各题最高分之和' 这条口径
 * （docs/exam-process.md §7）出现在重算、分布直方图、概览统计三处；三份手写 SQL 里任何一份
 * 少算一个 coalesce，就会让"直方图各档之和 != 总人数"这类不一致在线上静默存在。
 *
 * 片段内引用参数 {@code contestId}，因此只允许被同样以该名字传参的查询内联使用。
 */
final class ScoreAggregateSql {

    /**
     * 每个学生一行：总分、解决题数、最后一次判决时间。外层查询必须给它起别名后当子查询用。
     *
     * {@code coalesce(problem_id::text, submission_id::text)} 是"题目键"：判决事件里 problem_id 允许为空，
     * 为空时以 submission_id 为键——若直接 group by problem_id，所有缺题目归属的提交会被塞进同一个
     * null 桶，把互不相干的提交算成"同一道题的最高分"。
     *
     * {@code solved_count} 统计存在过 ACCEPTED 判决的题目数，而不是"最高分 > 0"的题目数：
     * 部分分方案下后者会把只拿到部分分的题也算作已解决（两种口径给出的名次会不一样）。
     */
    static final String PER_STUDENT_TOTALS = """
            select per_problem.student_id as student_id,
                   coalesce(sum(per_problem.best_score), 0)::int as total_score,
                   (count(*) filter (where per_problem.has_accepted))::int as solved_count,
                   max(per_problem.last_judged_at) as last_effective_at
            from (
                select student_id,
                       coalesce(problem_id::text, submission_id::text) as problem_key,
                       max(coalesce(score, 0)) as best_score,
                       max(judged_at) as last_judged_at,
                       bool_or(verdict = 'ACCEPTED') as has_accepted
                from analytics_schema.submission_facts
                where contest_id = #{contestId}
                group by student_id, coalesce(problem_id::text, submission_id::text)
            ) per_problem
            group by per_problem.student_id
            """;

    private ScoreAggregateSql() {
    }
}
