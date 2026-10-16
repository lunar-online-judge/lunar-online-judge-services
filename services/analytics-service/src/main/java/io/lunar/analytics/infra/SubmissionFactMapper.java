package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.lunar.analytics.domain.dto.LabeledCount;
import io.lunar.analytics.domain.dto.ProblemResult;
import io.lunar.analytics.domain.dto.ProblemStats;
import io.lunar.analytics.domain.dto.ScoreAggregate;
import io.lunar.analytics.domain.dto.ScoreBucket;
import io.lunar.analytics.domain.dto.ScoreSummary;
import io.lunar.analytics.domain.dto.SubmissionTimelineEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * 提交事实的写入入口。
 *
 * 为什么写入用显式 upsert 而不是 BaseMapper 的 insert/updateById：这两个方法都要求调用方先知道
 * "这一行在不在"，而 judged 事件可以被重复投递、两个生产者的到达顺序也无法假定；
 * 把判断放进数据库的 {@code on conflict}，重复与竞态都收敛成同一条语句。
 */
@Mapper
public interface SubmissionFactMapper extends BaseMapper<SubmissionFactEntity> {

    /**
     * 按 submission_id 收敛写入：已存在则整体覆盖为本次判决的内容，返回受影响行数（插入 1、覆盖 1、被冲突策略跳过 0）。
     *
     * 覆盖而不是"仅补空字段"：重复判决的语义就是终态以最后一次为准，且两个生产者的 payload 形状一致，
     * 覆盖不会把已有信息抹成空——contestId/studentId 等身份字段两路都会携带。
     */
    @Insert("insert into analytics_schema.submission_facts(submission_id, event_id, contest_id, problem_id, "
            + "problem_version_id, student_id, language, verdict, score, passed_cases, total_cases, "
            + "submitted_at, judged_at) "
            + "values (#{submissionId}, #{eventId}, #{contestId}, #{problemId}, #{problemVersionId}, "
            + "#{studentId}, #{language}, #{verdict}, #{score}, #{passedCases}, #{totalCases}, "
            + "#{submittedAt}, #{judgedAt}) "
            + "on conflict (submission_id) do update set event_id = excluded.event_id, "
            + "contest_id = excluded.contest_id, problem_id = excluded.problem_id, "
            + "problem_version_id = excluded.problem_version_id, student_id = excluded.student_id, "
            + "language = excluded.language, verdict = excluded.verdict, score = excluded.score, "
            + "passed_cases = excluded.passed_cases, total_cases = excluded.total_cases, "
            + "submitted_at = excluded.submitted_at, judged_at = excluded.judged_at")
    int upsert(@Param("submissionId") String submissionId,
               @Param("eventId") String eventId,
               @Param("contestId") String contestId,
               @Param("problemId") String problemId,
               @Param("problemVersionId") String problemVersionId,
               @Param("studentId") String studentId,
               @Param("language") String language,
               @Param("verdict") String verdict,
               @Param("score") Integer score,
               @Param("passedCases") Integer passedCases,
               @Param("totalCases") Integer totalCases,
               @Param("submittedAt") Instant submittedAt,
               @Param("judgedAt") Instant judgedAt);

    /**
     * 某场考试里每个学生的分数聚合（重算榜单的输入）。
     *
     * 重算在 Java 侧排序（tie-break 是可配的，见 RankingProperties）：SQL 只负责把"每题最高分"
     * 这个口径算对，名次交给配置驱动。
     */
    @Select(ScoreAggregateSql.PER_STUDENT_TOTALS)
    List<ScoreAggregate> selectScoreAggregates(@Param("contestId") String contestId);

    /** 分数直方图：按档宽把"每个学生的总分"分桶，只返回有人的档（空档由前端补零）。 */
    @Select("""
            select (totals.total_score / #{bucketSize}) * #{bucketSize} as bucket_from,
                   (totals.total_score / #{bucketSize}) * #{bucketSize} + #{bucketSize} - 1 as bucket_to,
                   count(*)::int as student_count
            from (
            """ + ScoreAggregateSql.PER_STUDENT_TOTALS + """
            ) totals
            group by 1, 2
            order by 1
            """)
    List<ScoreBucket> selectScoreBuckets(@Param("contestId") String contestId,
                                         @Param("bucketSize") int bucketSize);

    /** 概览统计：与直方图取自同一次口径，保证"各档之和 = 总人数"。 */
    @Select("""
            select count(*)::int as student_count,
                   avg(totals.total_score)::float8 as average_score,
                   max(totals.total_score)::int as max_score
            from (
            """ + ScoreAggregateSql.PER_STUDENT_TOTALS + """
            ) totals
            """)
    ScoreSummary selectScoreSummary(@Param("contestId") String contestId);

    /**
     * 题目维度统计。以 contest_problem_refs 为驱动表左连事实：**没有任何提交的题目也要出现在列表里**
     * （attempts=0），否则老师看不到"这道题全军覆没"这一最有价值的信息。
     *
     * <p>{@code problemId} 非空时退化为单题统计（同一份 SQL 复用，避免两处口径漂移）；
     * 排序用参照数据的 sort_order（出题人编排的顺序），并以 problem_id 兜底保证稳定。
     */
    @Select("""
            <script>
            select p.problem_id as problem_id,
                   p.problem_version_id as problem_version_id,
                   p.sort_order as sort_order,
                   coalesce(s.attempts, 0)::int as attempts,
                   coalesce(s.accepted_count, 0)::int as accepted_count,
                   coalesce(s.distinct_students, 0)::int as distinct_students,
                   s.average_score as average_score,
                   s.average_elapsed_seconds as average_elapsed_seconds,
                   s.first_attempt_at as first_attempt_at,
                   s.last_attempt_at as last_attempt_at
            from analytics_schema.contest_problem_refs p
            left join (
                select problem_id,
                       count(*) as attempts,
                       count(*) filter (where verdict = 'ACCEPTED') as accepted_count,
                       count(distinct student_id) as distinct_students,
                       avg(score)::float8 as average_score,
                       avg(extract(epoch from (judged_at - submitted_at)))::float8 as average_elapsed_seconds,
                       min(judged_at) as first_attempt_at,
                       max(judged_at) as last_attempt_at
                from analytics_schema.submission_facts
                where contest_id = #{contestId} and problem_id is not null
                group by problem_id
            ) s on s.problem_id = p.problem_id
            where p.contest_id = #{contestId}
            <if test="problemId != null"> and p.problem_id = #{problemId} </if>
            order by p.sort_order asc nulls last, p.problem_id asc
            limit #{limit} offset #{offset}
            </script>
            """)
    List<ProblemStats> selectProblemStats(@Param("contestId") String contestId,
                                          @Param("problemId") String problemId,
                                          @Param("limit") long limit,
                                          @Param("offset") long offset);

    /** 题目统计的总数（与上一条同样的过滤条件，分页 total 才不会与页内数据矛盾）。 */
    @Select("""
            <script>
            select count(*)::int
            from analytics_schema.contest_problem_refs p
            where p.contest_id = #{contestId}
            <if test="problemId != null"> and p.problem_id = #{problemId} </if>
            </script>
            """)
    int countProblemStats(@Param("contestId") String contestId, @Param("problemId") String problemId);

    /** 某学生在这场比赛里的判定结论分布。 */
    @Select("""
            select coalesce(verdict, 'UNKNOWN') as label, count(*)::int as item_count
            from analytics_schema.submission_facts
            where contest_id = #{contestId} and student_id = #{studentId}
            group by 1
            order by 2 desc, 1 asc
            """)
    List<LabeledCount> selectVerdictCounts(@Param("contestId") String contestId,
                                           @Param("studentId") String studentId);

    /** 某学生在这场比赛里的语言分布。 */
    @Select("""
            select coalesce(language, 'UNKNOWN') as label, count(*)::int as item_count
            from analytics_schema.submission_facts
            where contest_id = #{contestId} and student_id = #{studentId}
            group by 1
            order by 2 desc, 1 asc
            """)
    List<LabeledCount> selectLanguageCounts(@Param("contestId") String contestId,
                                            @Param("studentId") String studentId);

    /**
     * 某学生逐题的结果（学情视图的题目维度）。
     *
     * {@code last_verdict} 取最后一次判决的结论：学生真正想知道的是"最近一次过没过"，
     * 而 best_score 是"历史最好成绩"，两者故意分开（docs/exam-process.md §7 要求同屏显示）。
     */
    @Select("""
            select coalesce(problem_id::text, submission_id::text) as problem_id,
                   max(coalesce(score, 0))::int as best_score,
                   count(*)::int as attempts,
                   (array_agg(verdict order by judged_at desc, submission_id desc))[1] as last_verdict,
                   max(judged_at) as last_judged_at
            from analytics_schema.submission_facts
            where contest_id = #{contestId} and student_id = #{studentId}
            group by 1
            order by 1 asc
            """)
    List<ProblemResult> selectStudentProblemResults(@Param("contestId") String contestId,
                                                    @Param("studentId") String studentId);

    /**
     * 某学生的提交时间线（最近优先），只带判定结论，不带源码。
     *
     * {@code from}/{@code to} 可选：复盘"考试最后半小时他交了什么"需要时间窗，
     * 而整场考试的提交列表在长考试里会很长。窗口只作用于时间线，不改变题目维度的最高分口径
     * （带窗口的"最高分"不再是成绩，混在一起会让老师读错）。
     */
    @Select("""
            <script>
            select submission_id as submission_id,
                   coalesce(problem_id::text, submission_id::text) as problem_id,
                   verdict as verdict,
                   score as score,
                   judged_at as judged_at
            from analytics_schema.submission_facts
            where contest_id = #{contestId} and student_id = #{studentId}
            <if test="from != null"> and judged_at &gt;= #{from} </if>
            <if test="to != null"> and judged_at &lt;= #{to} </if>
            order by judged_at desc, submission_id desc
            limit #{limit}
            </script>
            """)
    List<SubmissionTimelineEntry> selectStudentTimeline(@Param("contestId") String contestId,
                                                        @Param("studentId") String studentId,
                                                        @Param("from") Instant from,
                                                        @Param("to") Instant to,
                                                        @Param("limit") long limit);

    /**
     * 需要重算成绩的考试：在窗口内有过判决、且**该判决比上次重算还新**的考试。
     *
     * 为什么不能只按"最近有判决"触发重算：正在进行的考试每几秒就有一次判决，
     * 那样等于每轮都把全场考试重算一遍。这里用 {@code computed_at}（重算时写入读模型的时间戳）
     * 与事实的 {@code judged_at} 比较，表达的是"读模型落后于事实"，与事实总量无关。
     *
     * {@code not exists} 同时覆盖两种"需要算"的情形：从来没有算过（子查询无行）与算过之后又有新判决。
     */
    @Select("""
            select distinct f.contest_id
            from analytics_schema.submission_facts f
            where f.judged_at >= #{since}
              and not exists (
                  select 1 from analytics_schema.student_contest_scores s
                  where s.contest_id = f.contest_id and s.computed_at >= f.judged_at
              )
            order by f.contest_id
            limit #{maxRows}
            """)
    List<String> selectContestIdsNeedingScoreRebuild(@Param("since") Instant since,
                                                     @Param("maxRows") int maxRows);

    /**
     * "最近有判决写入"的考试：定时任务据此挑选要刷新参照数据的考试，避免全表轮询所有历史竞赛。
     * 排序只为让同一轮次的执行结果可复现（排查"为什么这场没刷到"时要能重放）。
     */
    @Select("""
            select distinct contest_id
            from analytics_schema.submission_facts
            where judged_at >= #{since}
            order by contest_id
            """)
    List<String> selectContestIdsJudgedSince(@Param("since") Instant since);

    /**
     * 已有事实、但参照数据一行都没有的考试（最多返回 {@code maxRows} 条，按 contest_id 升序）。
     *
     * 存在的意义是"自愈"：参照数据的定时刷新只覆盖活跃考试，一个早就结束、后来才补进事实的考试
     * 会永远没有竞赛名与题目清单。分批返回而不是一次全给：历史数据首次上线时这条查询可能命中很多场考试，
     * 一轮刷不完没关系——刷过的下一轮就不再"缺参照数据"，批次会自动向前推进。
     */
    @Select("""
            select distinct f.contest_id
            from analytics_schema.submission_facts f
            left join analytics_schema.contest_refs r on r.contest_id = f.contest_id
            where r.contest_id is null
            order by f.contest_id
            limit #{maxRows}
            """)
    List<String> selectContestIdsMissingRefs(@Param("maxRows") int maxRows);
}
