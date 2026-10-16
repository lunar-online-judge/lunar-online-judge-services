package io.lunar.analytics.domain;

import io.lunar.analytics.domain.dto.AnalyticsPage;
import io.lunar.analytics.domain.dto.MyScoreView;
import io.lunar.analytics.domain.dto.ScoreDistributionView;
import io.lunar.analytics.domain.dto.ScoreboardRow;
import io.lunar.analytics.domain.dto.ScoreSummary;
import io.lunar.analytics.infra.StudentContestScoreMapper;
import io.lunar.analytics.infra.SubmissionFactMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 成绩与排名的查询面。
 *
 * 读取一律走物化表（student_contest_scores），而不是每次从提交事实重新聚合：
 * 名次是全局序号，实时算意味着每翻一页都要把整场考试重新聚合排序。
 *
 * 自愈：物化表为空时顺带重算一次（见 {@link #ensureComputed}）——它覆盖"重算任务还没跑到"
 * 与"服务刚部署、历史考试从没算过"两种情况，让首个查询者不必等定时任务。
 */
@Service
public class ScoreboardService {

    private static final int MIN_BUCKET_SIZE = 1;
    private static final int MAX_BUCKET_SIZE = 100;

    private final StudentContestScoreMapper studentContestScoreMapper;
    private final SubmissionFactMapper submissionFactMapper;
    private final AnalyticsRebuildService analyticsRebuildService;
    private final AnalyticsProperties analyticsProperties;

    public ScoreboardService(StudentContestScoreMapper studentContestScoreMapper,
                             SubmissionFactMapper submissionFactMapper,
                             AnalyticsRebuildService analyticsRebuildService,
                             AnalyticsProperties analyticsProperties) {
        this.studentContestScoreMapper = studentContestScoreMapper;
        this.submissionFactMapper = submissionFactMapper;
        this.analyticsRebuildService = analyticsRebuildService;
        this.analyticsProperties = analyticsProperties;
    }

    /**
     * 榜单分页。排序由重算时授予的 rank 决定（见 StudentContestScoreMapper#selectScoreboard），
     * 查询侧不重新排序，保证"同一份数据在任何接口里名次一致"。
     */
    public AnalyticsPage<ScoreboardRow> scoreboard(String contestId, long page, long pageSize) {
        AnalyticsPageRequest pageRequest = AnalyticsPageRequest.of(page, pageSize);
        ensureComputed(contestId);
        List<ScoreboardRow> rows = studentContestScoreMapper.selectScoreboard(
                contestId, null, pageRequest.pageSize(), pageRequest.offset());
        int total = studentContestScoreMapper.countByContest(contestId);
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
     * 单独暴露给学情视图复用：它要展示名次与总分，而"取一行 + 必要的兜底重算"这两步
     * 在学情里也必须一致，否则同一份数据在两个接口里会给出不同的名次。
     */
    public ScoreboardRow currentRow(String contestId, String studentId) {
        ensureComputed(contestId);
        List<ScoreboardRow> rows = studentContestScoreMapper.selectScoreboard(contestId, studentId, 1L, 0L);
        return rows.isEmpty() ? null : rows.get(0);
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
        ensureComputed(contestId);
        ScoreSummary summary = submissionFactMapper.selectScoreSummary(contestId);
        return new ScoreDistributionView(bucketSize, summary.studentCount(), summary.averageScore(),
                summary.maxScore(), submissionFactMapper.selectScoreBuckets(contestId, bucketSize));
    }

    /**
     * 读之前的兜底重算：物化表里一行都没有就试着算一次。
     *
     * 只处理"空表"这一种情况：非空但过期的表由定时任务与显式重建端点负责，
     * 读路径上做"是否过期"的判断要么引入时间戳比较的竞态，要么把整场考试的重算塞进用户请求。
     */
    private void ensureComputed(String contestId) {
        if (studentContestScoreMapper.countByContest(contestId) > 0) {
            return;
        }
        analyticsRebuildService.rebuildIfIdle(contestId);
    }
}
