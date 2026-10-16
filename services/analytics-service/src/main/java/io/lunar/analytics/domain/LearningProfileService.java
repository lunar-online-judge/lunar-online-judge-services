package io.lunar.analytics.domain;

import io.lunar.analytics.domain.dto.LabeledCount;
import io.lunar.analytics.domain.dto.ProblemResult;
import io.lunar.analytics.domain.dto.ScoreboardRow;
import io.lunar.analytics.domain.dto.StudentProfileView;
import io.lunar.analytics.domain.dto.SubmissionTimelineEntry;
import io.lunar.analytics.infra.SubmissionFactMapper;
import io.lunar.analytics.infra.dto.ContestSnapshotCandidateView;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 学情视图：按需从提交事实聚合，**不建物化表**。
 *
 * 为什么这里可以实时算而排名不行：单个学生的提交量被频率限制封顶，聚合是"一次索引扫描 + 分组"；
 * 而排名是全局序号，实时算要扫全场。物化学情还得额外定义"什么时候失效"——
 * 一处通知漏掉就会长期展示过期结论，收益不抵复杂度。
 *
 * 时间窗只作用于**提交时间线**：题目维度的 best_score 是"这场考试的最高分"，
 * 若它也跟着窗口变，老师会把"某段时间内的最高分"当成成绩读。
 */
@Service
public class LearningProfileService {

    /** 时间线最多返回的条数：学情面板是给人看的，翻不到底的历史提交应该在提交列表接口里查。 */
    private static final long MAX_TIMELINE_ITEMS = 50L;

    private final SubmissionFactMapper submissionFactMapper;
    private final ScoreboardService scoreboardService;

    public LearningProfileService(SubmissionFactMapper submissionFactMapper, ScoreboardService scoreboardService) {
        this.submissionFactMapper = submissionFactMapper;
        this.scoreboardService = scoreboardService;
    }

    /**
     * 组装一个学生的学情档案。
     *
     * 名次与总分取自成绩读模型（与榜单同源），做题过程取自提交事实；
     * {@code candidate} 由调用方从考试快照里取（它同时承担"这个学生确实在这场考试里"的校验）。
     */
    public StudentProfileView profile(String contestId, ContestSnapshotCandidateView candidate,
                                     Instant from, Instant to) {
        String studentId = candidate.studentId();
        ScoreboardRow scoreRow = scoreboardService.currentRow(contestId, studentId);
        List<LabeledCount> verdictCounts = submissionFactMapper.selectVerdictCounts(contestId, studentId);
        List<LabeledCount> languageCounts = submissionFactMapper.selectLanguageCounts(contestId, studentId);
        List<ProblemResult> problems = submissionFactMapper.selectStudentProblemResults(contestId, studentId);
        List<SubmissionTimelineEntry> timeline =
                submissionFactMapper.selectStudentTimeline(contestId, studentId, from, to, MAX_TIMELINE_ITEMS);
        return new StudentProfileView(studentId, candidate.studentNo(), candidate.displayName(),
                candidate.enrollmentStatus(),
                scoreRow == null ? null : scoreRow.rank(),
                scoreRow == null ? null : scoreRow.totalScore(),
                scoreRow == null ? null : scoreRow.solvedCount(),
                scoreRow == null ? null : scoreRow.lastEffectiveAt(),
                verdictCounts, languageCounts, problems, timeline);
    }
}
