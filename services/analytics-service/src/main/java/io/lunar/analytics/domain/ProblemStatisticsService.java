package io.lunar.analytics.domain;

import io.lunar.analytics.domain.dto.AnalyticsPage;
import io.lunar.analytics.domain.dto.ProblemStats;
import io.lunar.analytics.infra.SubmissionFactMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 学情的**题目维度**：每道题的尝试次数、通过数、平均分与平均耗时。
 *
 * 为什么以参照数据（contest_problem_refs）为驱动表：一场考试里"没人做出来的题"是最该被看见的信息，
 * 若只从提交事实聚合，它根本不出现——列表会静默地少一行，而读者不会发现。
 *
 * 与 {@link LearningProfileService}（学生维度）分开的理由：两者的输入不同（参照表 vs 单生事实）、
 * 权限校验的对象也不同（前者按考试、后者要额外确认学生在册），合在一起只会让方法名越来越长。
 */
@Service
public class ProblemStatisticsService {

    private final SubmissionFactMapper submissionFactMapper;

    public ProblemStatisticsService(SubmissionFactMapper submissionFactMapper) {
        this.submissionFactMapper = submissionFactMapper;
    }

    /** 题目统计分页；排序沿用出题人编排的 sort_order（见 Mapper 里的 SQL）。 */
    public AnalyticsPage<ProblemStats> problems(String contestId, long page, long pageSize) {
        AnalyticsPageRequest pageRequest = AnalyticsPageRequest.of(page, pageSize);
        List<ProblemStats> items = submissionFactMapper.selectProblemStats(
                contestId, null, pageRequest.pageSize(), pageRequest.offset());
        int total = submissionFactMapper.countProblemStats(contestId, null);
        return new AnalyticsPage<>(items, total, pageRequest.page(), pageRequest.pageSize());
    }

    /**
     * 单题统计。
     *
     * 题目不属于这场考试时返回 404（与"这场考试不存在"同码）：区分二者会泄露
     * "这个题目 ID 是真实存在的"。
     */
    public ProblemStats problemStats(String contestId, String problemId) {
        List<ProblemStats> stats = submissionFactMapper.selectProblemStats(contestId, problemId, 1L, 0L);
        if (stats.isEmpty()) {
            throw new AnalyticsException.ResourceNotFound();
        }
        return stats.get(0);
    }
}
