package io.lunar.analytics.domain;

import io.lunar.analytics.domain.dto.AnalyticsPage;
import io.lunar.analytics.domain.dto.CandidateRef;
import io.lunar.analytics.domain.dto.MyScoreView;
import io.lunar.analytics.domain.dto.ScoreAggregate;
import io.lunar.analytics.domain.dto.ScoreDistributionView;
import io.lunar.analytics.domain.dto.ScoreSummary;
import io.lunar.analytics.domain.dto.ScoreboardRow;
import io.lunar.analytics.infra.ScoreboardQueryMapper;
import io.lunar.analytics.infra.SubmissionFactMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 成绩与排名的查询面。
 *
 * **实时聚合，不读物化表**（简化：原实现维护 `student_contest_scores` 物化表，由重算服务写入、
 * 读路径自愈兜底，共约 245 行）。在学校规模（几百人、几千次提交）下，每次查询直接对
 * `submission_facts` 聚合一次、内存排序分页完全够用，换来的是：
 *   - 没有"物化表落后于事实"的窗口（读到的永远是最新判决）；
 *   - 没有重算/自愈/物化的并发与幂等负担；
 *   - 名次只有一处来源（{@link RankingOrder}），不会出现"重算时一个名次、查询时另一个名次"。
 *
 * 代价：每次查询做一次聚合 + 全量排序。真到数千人规模再考虑物化——那时已经有
 * {@link io.lunar.analytics.infra.ScoreAggregateSql} 的集成测试兜底。
 */
@Service
public class ScoreboardService {

    private static final int MIN_BUCKET_SIZE = 1;
    private static final int MAX_BUCKET_SIZE = 100;

    private final SubmissionFactMapper submissionFactMapper;
    private final ScoreboardQueryMapper scoreboardQueryMapper;
    private final RankingOrder rankingOrder;
    private final AnalyticsProperties analyticsProperties;

    public ScoreboardService(SubmissionFactMapper submissionFactMapper,
                             ScoreboardQueryMapper scoreboardQueryMapper,
                             RankingOrder rankingOrder,
                             AnalyticsProperties analyticsProperties) {
        this.submissionFactMapper = submissionFactMapper;
        this.scoreboardQueryMapper = scoreboardQueryMapper;
        this.rankingOrder = rankingOrder;
        this.analyticsProperties = analyticsProperties;
    }

    /**
     * 榜单分页。
     *
     * 名次是**全局序号**（不是页内序号）：先整体排序再切片，所以翻到第二页时名次仍然连续可解释。
     */
    public AnalyticsPage<ScoreboardRow> scoreboard(String contestId, long page, long pageSize) {
        AnalyticsPageRequest pageRequest = AnalyticsPageRequest.of(page, pageSize);
        List<ScoreAggregate> ranked = rankedAggregates(contestId);
        int total = ranked.size();

        int fromIndex = (int) Math.min(pageRequest.offset(), total);
        int toIndex = (int) Math.min(fromIndex + (int) pageRequest.pageSize(), total);
        List<ScoreAggregate> slice = ranked.subList(fromIndex, toIndex);

        Instant computedAt = Instant.now();
        Map<String, CandidateRef> refs = candidateRefs(contestId, slice);
        List<ScoreboardRow> rows = new ArrayList<>(slice.size());
        for (int index = fromIndex; index < toIndex; index++) {
            rows.add(toRow(ranked.get(index), index + 1, refs, computedAt));
        }
        return new AnalyticsPage<>(rows, total, pageRequest.page(), pageRequest.pageSize());
    }

    /**
     * 考生看自己那一行。
     *
     * 不可见时返回 {@code visibility=HIDDEN} 而不是 404：调用方需要区分"你无权看成绩"与
     * "这场考试/这个资源不存在"——后者会让考生以为考试被删了。
     */
    public MyScoreView myScore(String contestId, String studentId) {
        ScoreVisibility visibility = analyticsProperties.getScoreVisibility();
        if (visibility == ScoreVisibility.HIDDEN) {
            return new MyScoreView(visibility.name(), false, null, null, null, null, null);
        }
        ScoreboardRow row = currentRow(contestId, studentId);
        if (row == null) {
            // 在册但一次都没提交：不是错误，只是还没有成绩行
            return new MyScoreView(visibility.name(), false, null, null, null, null, null);
        }
        return new MyScoreView(visibility.name(), true, row.rank(), row.totalScore(), row.solvedCount(),
                row.lastEffectiveAt(), row.computedAt());
    }

    /**
     * 取某个学生的成绩行（可能为空：该生一次都没提交）。
     *
     * 暴露给学情视图复用：它要展示名次与总分，而名次必须与榜单接口一致——所以两者都走
     * {@link #rankedAggregates}，不各自排序。
     */
    public ScoreboardRow currentRow(String contestId, String studentId) {
        List<ScoreAggregate> ranked = rankedAggregates(contestId);
        for (int index = 0; index < ranked.size(); index++) {
            ScoreAggregate aggregate = ranked.get(index);
            if (aggregate.studentId().equals(studentId)) {
                Map<String, CandidateRef> refs = candidateRefs(contestId, List.of(aggregate));
                return toRow(aggregate, index + 1, refs, Instant.now());
            }
        }
        return null;
    }

    /**
     * 总分分布直方图。
     *
     * 档宽由调用方给（避免把"10 分一档"写死进前端），但必须有界：档宽为 1 且满分 100 时会产生 100 个桶，
     * 已经是可接受的极限；再小就是拿接口当导出用。
     */
    public ScoreDistributionView distribution(String contestId, int bucketSize) {
        if (bucketSize < MIN_BUCKET_SIZE || bucketSize > MAX_BUCKET_SIZE) {
            throw new AnalyticsException.InvalidRange(
                    "bucketSize 必须在 " + MIN_BUCKET_SIZE + " 到 " + MAX_BUCKET_SIZE + " 之间");
        }
        ScoreSummary summary = submissionFactMapper.selectScoreSummary(contestId);
        return new ScoreDistributionView(bucketSize, summary.studentCount(), summary.averageScore(),
                summary.maxScore(), submissionFactMapper.selectScoreBuckets(contestId, bucketSize));
    }

    /** 聚合 + 排序：名次的唯一产生处。 */
    private List<ScoreAggregate> rankedAggregates(String contestId) {
        List<ScoreAggregate> aggregates = new ArrayList<>(submissionFactMapper.selectScoreAggregates(contestId));
        aggregates.sort(rankingOrder.comparator());
        return aggregates;
    }

    private Map<String, CandidateRef> candidateRefs(String contestId, List<ScoreAggregate> aggregates) {
        if (aggregates.isEmpty()) {
            return Map.of();
        }
        List<String> studentIds = aggregates.stream().map(ScoreAggregate::studentId).toList();
        Map<String, CandidateRef> refs = new HashMap<>();
        for (CandidateRef ref : scoreboardQueryMapper.selectCandidateRefs(contestId, studentIds)) {
            refs.put(ref.studentId(), ref);
        }
        return refs;
    }

    private static ScoreboardRow toRow(ScoreAggregate aggregate, int rank,
                                       Map<String, CandidateRef> refs, Instant computedAt) {
        CandidateRef ref = refs.get(aggregate.studentId());
        return new ScoreboardRow(rank, aggregate.studentId(),
                ref == null ? null : ref.studentNo(),
                ref == null ? null : ref.displayName(),
                aggregate.totalScore(), aggregate.solvedCount(), aggregate.lastEffectiveAt(), computedAt);
    }
}
