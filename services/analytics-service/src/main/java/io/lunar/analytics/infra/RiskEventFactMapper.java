package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.lunar.analytics.domain.dto.LabeledCount;
import io.lunar.analytics.domain.dto.RiskHeatCell;
import io.lunar.analytics.domain.dto.RiskStudentRow;
import io.lunar.analytics.domain.dto.RiskSummaryCounts;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * 风险事件事实的写入入口。
 *
 * 创建事件与审核流转事件来自两个不同的消费组，各自只掌握这条事实的一部分：
 * 前者有事件的静态属性，后者有审核结论。两条 upsert 因此都只能"覆盖自己负责的列"，
 * 否则后到的那条会把先到的那条写进去的列清空（analyitcs 侧表现为 severity 或 review_status 莫名变空）。
 */
@Mapper
public interface RiskEventFactMapper extends BaseMapper<RiskEventFactEntity> {

    /**
     * 风险事件创建（lunar-risk-event-created）：插入或补齐事件的静态属性。
     *
     * {@code on conflict do nothing} 而非 do update：审核流转事件可能先到（两个 Outbox 行的投递顺序无保证），
     * 此时行已存在且 review_status 已是审核结论；创建事件若覆盖，就会把结论退回默认的 OPEN。
     * 反过来，创建事件总是携带最全的事件属性，先到的情况下也不会被审核事件写空（后者不动这些列）。
     */
    @Insert("insert into analytics_schema.risk_event_facts(event_id, contest_id, student_id, problem_id, "
            + "event_type, severity, occurred_at, review_status) "
            + "values (#{eventId}, #{contestId}, #{studentId}, #{problemId}, #{eventType}, #{severity}, "
            + "#{occurredAt}, 'OPEN') "
            + "on conflict (event_id) do nothing")
    int insertIfAbsent(@Param("eventId") String eventId,
                       @Param("contestId") String contestId,
                       @Param("studentId") String studentId,
                       @Param("problemId") String problemId,
                       @Param("eventType") String eventType,
                       @Param("severity") String severity,
                       @Param("occurredAt") Instant occurredAt);

    /**
     * 风险事件审核流转（lunar-risk-event-reviewed）：写入审核结论。
     *
     * 为什么是 insert … on conflict do update 而不是纯 update：审核消息可能先于创建消息到达，
     * 纯 update 会一行都改不到（那条结论就此丢失，消息却已被 ack）。两条消息都是 anti-cheat 在同一事务里
     * 发出的，正常顺序下创建先行、这里只会走 update 分支。
     */
    @Insert("insert into analytics_schema.risk_event_facts(event_id, contest_id, student_id, event_type, "
            + "review_status, reviewed_at, reviewed_by) "
            + "values (#{eventId}, #{contestId}, #{studentId}, #{eventType}, #{reviewStatus}, "
            + "#{reviewedAt}, #{reviewedBy}) "
            + "on conflict (event_id) do update set review_status = excluded.review_status, "
            + "reviewed_at = excluded.reviewed_at, reviewed_by = excluded.reviewed_by")
    int upsertReview(@Param("eventId") String eventId,
                     @Param("contestId") String contestId,
                     @Param("studentId") String studentId,
                     @Param("eventType") String eventType,
                     @Param("reviewStatus") String reviewStatus,
                     @Param("reviewedAt") Instant reviewedAt,
                     @Param("reviewedBy") String reviewedBy);

    /** 风险汇总的标量计数（一次查询取全，避免多次查询之间审核状态被改而自相矛盾）。 */
    @Select("""
            select count(*)::int as total_events,
                   (count(*) filter (where review_status in ('OPEN', 'ACKNOWLEDGED')))::int as pending_review,
                   (count(*) filter (where review_status = 'CONFIRMED'))::int as confirmed_count,
                   (count(*) filter (where review_status = 'DISMISSED'))::int as dismissed_count,
                   count(distinct student_id)::int as students_with_risk
            from analytics_schema.risk_event_facts
            where contest_id = #{contestId}
            """)
    RiskSummaryCounts selectRiskSummaryCounts(@Param("contestId") String contestId);

    /** 按严重度分布；严重度允许为空（未知类型的事件），归到 UNKNOWN 而不是消失。 */
    @Select("""
            select coalesce(severity, 'UNKNOWN') as label, count(*)::int as item_count
            from analytics_schema.risk_event_facts
            where contest_id = #{contestId}
            group by 1
            order by 2 desc, 1 asc
            """)
    List<LabeledCount> selectRiskCountsBySeverity(@Param("contestId") String contestId);

    /** 按事件类型分布：类型是 not null，因此不需要兜底标签。 */
    @Select("""
            select event_type as label, count(*)::int as item_count
            from analytics_schema.risk_event_facts
            where contest_id = #{contestId}
            group by 1
            order by 2 desc, 1 asc
            """)
    List<LabeledCount> selectRiskCountsByEventType(@Param("contestId") String contestId);

    /** 按审核状态分布：与状态机的取值域同名（OPEN/ACKNOWLEDGED/CONFIRMED/DISMISSED）。 */
    @Select("""
            select review_status as label, count(*)::int as item_count
            from analytics_schema.risk_event_facts
            where contest_id = #{contestId}
            group by 1
            order by 2 desc, 1 asc
            """)
    List<LabeledCount> selectRiskCountsByReviewStatus(@Param("contestId") String contestId);

    /**
     * 学生×时间窗热力图：返回**最近**的若干格子（时间倒序 + 行数上限）。
     *
     * 截断方向是刻意的：按时间正序取前 N 个会把最新的监考信息丢掉，而监考关心的是"刚刚发生了什么"。
     * 上限由 {@code analytics.risk.heatmap-max-buckets} 控制——一次查询铺开整场考试的事件格子，
     * 只会让这个接口在真实考试规模下不可用。
     *
     * {@code occurredAt} 为空的事件没有时间维度，直接排除（用 coalesce 造一个时间等于编造发生时刻）。
     */
    @Select("""
            select student_id as student_id,
                   to_timestamp((floor(extract(epoch from occurred_at) / #{bucketSeconds}) * #{bucketSeconds})::float8)
                       as window_start,
                   count(*)::int as item_count
            from analytics_schema.risk_event_facts
            where contest_id = #{contestId} and occurred_at is not null
            group by 1, 2
            order by 2 desc, 1 asc
            limit #{maxCells}
            """)
    List<RiskHeatCell> selectRiskHeatmap(@Param("contestId") String contestId,
                                         @Param("bucketSeconds") int bucketSeconds,
                                         @Param("maxCells") int maxCells);

    /**
     * 按学生聚合的风险列表（可疑度降序，分页）。
     *
     * **可疑度公式在本仓库唯一定义在这条 SQL 里**，权重来自 {@code analytics.risk.weight-*}：
     * 排序必须发生在数据库里才能配合 offset 分页，若改用 Java 侧排序就得把整场考试的学生全量取回再切片。
     * 权重口径是产品决策（未经确认），因此全部外置为配置；改权重不需要改代码。
     *
     * 最后一档排序键是 student_id：没有它，同可疑度的学生在翻页时顺序不稳定，会出现"某学生翻两页各出现一次"。
     * AI 结论的分数**不进入**这个公式：模型输出可被提示注入影响，不该决定人对学生的怀疑程度。
     */
    @Select("""
            select student_id as student_id,
                   ((count(*) filter (where severity = 'HIGH')) * #{weightHigh}
                    + (count(*) filter (where review_status = 'CONFIRMED')) * #{weightConfirmed}
                    + (count(*) filter (where review_status = 'ACKNOWLEDGED')) * #{weightAcknowledged}
                    + (count(*) filter (where review_status = 'OPEN')) * #{weightPending})::int as risk_score,
                   count(*)::int as event_count,
                   (count(*) filter (where severity = 'HIGH'))::int as high_severity_count,
                   (count(*) filter (where review_status = 'CONFIRMED'))::int as confirmed_count,
                   (count(*) filter (where review_status in ('OPEN', 'ACKNOWLEDGED')))::int as pending_count,
                   max(occurred_at) as latest_occurred_at
            from analytics_schema.risk_event_facts
            where contest_id = #{contestId}
            group by student_id
            order by risk_score desc, student_id asc
            limit #{limit} offset #{offset}
            """)
    List<RiskStudentRow> selectRiskStudents(@Param("contestId") String contestId,
                                            @Param("weightHigh") int weightHigh,
                                            @Param("weightConfirmed") int weightConfirmed,
                                            @Param("weightAcknowledged") int weightAcknowledged,
                                            @Param("weightPending") int weightPending,
                                            @Param("limit") long limit,
                                            @Param("offset") long offset);

    /** 风险学生列表的总数（分页 total）。 */
    @Select("""
            select count(distinct student_id)::int
            from analytics_schema.risk_event_facts
            where contest_id = #{contestId}
            """)
    int countRiskStudents(@Param("contestId") String contestId);

    /** "最近有风险事件写入"的考试：定时任务据此挑选要刷新参照数据的考试。 */
    @Select("""
            select distinct contest_id
            from analytics_schema.risk_event_facts
            where occurred_at >= #{since}
            """)
    List<String> selectContestIdsOccurredSince(@Param("since") Instant since);
}
