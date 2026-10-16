package io.lunar.analytics.api;

import io.lunar.analytics.domain.AnalyticsException;
import io.lunar.analytics.domain.AnalyticsScopeGuard;
import io.lunar.analytics.domain.Identifiers;
import io.lunar.analytics.domain.LearningProfileService;
import io.lunar.analytics.domain.ProblemStatisticsService;
import io.lunar.analytics.domain.RiskAnalyticsService;
import io.lunar.analytics.domain.ScoreboardService;
import io.lunar.analytics.domain.dto.AnalyticsPage;
import io.lunar.analytics.domain.dto.MyScoreView;
import io.lunar.analytics.domain.dto.ProblemStats;
import io.lunar.analytics.domain.dto.RiskStudentView;
import io.lunar.analytics.domain.dto.RiskSummaryView;
import io.lunar.analytics.domain.dto.ScoreDistributionView;
import io.lunar.analytics.domain.dto.ScoreboardRow;
import io.lunar.analytics.domain.dto.StudentProfileView;
import io.lunar.analytics.infra.dto.ContestSnapshotCandidateView;
import io.lunar.analytics.infra.dto.ContestSnapshotView;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.api.Pagination;
import io.lunar.common.security.AccessScope;
import io.lunar.common.web.RequestIdFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 分析读模型入口（`docs/api.md` 目前**没有**独立的"分析"小节：端点、方法与权限见该文件的**端点总表**，错误码见其中的**错误码表**。此处原先指向一个并不存在的小节，属悬空引用，已改为指向真实位置）。
 *
 * 三层权限，缺一不可（与 ai-service 同一套约定）：
 *   ① 角色：{@code @PreAuthorize} —— 成绩与学情给 TEACHER/ADMIN，风险证据给 ADMIN，考生只能看自己那一行；
 *   ② 资源范围：{@link AnalyticsScopeGuard} 按考试归属判断（越权与不存在同样 404）；
 *   ③ 身份来源：学生身份与角色只从**已验证令牌**取，绝不读请求参数（否则考生传个 studentId 就能看别人）。
 *
 * 为什么风险证据收紧为 ADMIN：跨服务的"考试级授权"尚未落地，而风险结论的处置影响学生权益，
 * 在能按考试归属授权之前先按最小权限开放（与 anti-cheat 的 /monitor/events 同一取舍）。
 */
@RestController
@RequestMapping("/api/v1/analytics/contests/{contestId}")
public class AnalyticsController {

    private static final String DEFAULT_BUCKET_SIZE = "10";

    private final AnalyticsScopeGuard scopeGuard;
    private final ScoreboardService scoreboardService;
    private final LearningProfileService learningProfileService;
    private final ProblemStatisticsService problemStatisticsService;
    private final RiskAnalyticsService riskAnalyticsService;

    public AnalyticsController(AnalyticsScopeGuard scopeGuard,
                               ScoreboardService scoreboardService,
                               LearningProfileService learningProfileService,
                               ProblemStatisticsService problemStatisticsService,
                               RiskAnalyticsService riskAnalyticsService) {
        this.scopeGuard = scopeGuard;
        this.scoreboardService = scoreboardService;
        this.learningProfileService = learningProfileService;
        this.problemStatisticsService = problemStatisticsService;
        this.riskAnalyticsService = riskAnalyticsService;
    }

    /** 成绩榜（分页）：名次由成绩读模型授予，查询侧不重排。 */
    @GetMapping("/scoreboard")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AnalyticsPage<ScoreboardRow>>> scoreboard(
            @PathVariable String contestId,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        scopeGuard.requireContestAccess(contestId, AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId,
                scoreboardService.scoreboard(contestId, page, pageSize)));
    }

    /**
     * 考生看自己的成绩。
     *
     * 学生身份取令牌的 sub，接口**不接受** studentId 参数：一旦接受，任何考生都能查到同学的成绩。
     */
    @GetMapping("/scoreboard/me")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApiResponse<MyScoreView>> myScore(
            @PathVariable String contestId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        scopeGuard.requireCandidateAccess(contestId, authentication);
        return ResponseEntity.ok(ApiResponse.success(requestId,
                scoreboardService.myScore(contestId, authentication.getName())));
    }

    /**
     * 总分分布直方图。
     *
     * {@code bucketSize} 出界返回 400 {@code ANALYTICS_INVALID_RANGE}（而不是夹取）：
     * 夹取会让调用方以为"10 分一档"生效了，而实际返回的是别的档宽——分布图的坐标轴会整体错位。
     */
    @GetMapping("/score-distribution")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ScoreDistributionView>> scoreDistribution(
            @PathVariable String contestId,
            @RequestParam(defaultValue = DEFAULT_BUCKET_SIZE) int bucketSize,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        scopeGuard.requireContestAccess(contestId, AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId,
                scoreboardService.distribution(contestId, bucketSize)));
    }

    /** 题目维度统计（含无人提交的题目）。 */
    @GetMapping("/problems")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AnalyticsPage<ProblemStats>>> problems(
            @PathVariable String contestId,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        scopeGuard.requireContestAccess(contestId, AccessScope.of(authentication));
        return ResponseEntity.ok(ApiResponse.success(requestId,
                problemStatisticsService.problems(contestId, page, pageSize)));
    }

    /** 单题统计；题目不属于这场考试时与"考试不存在"同码（404）。 */
    @GetMapping("/problems/{problemId}/stats")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ProblemStats>> problemStats(
            @PathVariable String contestId,
            @PathVariable String problemId,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        scopeGuard.requireContestAccess(contestId, AccessScope.of(authentication));
        String validatedProblemId = Identifiers.requireUuid(problemId);
        return ResponseEntity.ok(ApiResponse.success(requestId,
                problemStatisticsService.problemStats(contestId, validatedProblemId)));
    }

    /**
     * 单个学生的学情档案。
     *
     * {@code from}/{@code to}（ISO-8601）只作用于提交时间线；起止颠倒返回 400
     * {@code ANALYTICS_INVALID_RANGE}——空结果会让人以为"这段时间他没提交"。
     */
    @GetMapping("/students/{studentId}/profile")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<ApiResponse<StudentProfileView>> studentProfile(
            @PathVariable String contestId,
            @PathVariable String studentId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Authentication authentication,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        ContestSnapshotView snapshot = scopeGuard.requireContestAccess(contestId, AccessScope.of(authentication));
        String validatedStudentId = Identifiers.requireUuid(studentId);
        ContestSnapshotCandidateView candidate = findCandidate(snapshot, validatedStudentId);
        if (candidate == null) {
            throw new AnalyticsException.ResourceNotFound();
        }
        Instant fromInstant = parseOptionalInstant(from, "from");
        Instant toInstant = parseOptionalInstant(to, "to");
        requireOrderedRange(fromInstant, toInstant);
        return ResponseEntity.ok(ApiResponse.success(requestId,
                learningProfileService.profile(contestId, candidate, fromInstant, toInstant)));
    }

    /** 风险汇总（仅 ADMIN）：标量计数 + 严重度/类型/审核状态分布 + 学生×时间窗热力图。 */
    @GetMapping("/risk/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RiskSummaryView>> riskSummary(
            @PathVariable String contestId,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        scopeGuard.requireExistingContest(contestId);
        return ResponseEntity.ok(ApiResponse.success(requestId, riskAnalyticsService.summary(contestId)));
    }

    /**
     * 风险学生列表（仅 ADMIN，分页）：按可疑度降序，并附上该学生的 AI 结论作为证据。
     *
     * 仍要求考试存在：不存在的 contestId 会得到一张空列表，那看起来像"这场考试没有风险"——
     * 与"这个考试根本不存在"是完全不同的结论，必须区分。
     */
    @GetMapping("/risk/students")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AnalyticsPage<RiskStudentView>>> riskStudents(
            @PathVariable String contestId,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE) long page,
            @RequestParam(defaultValue = Pagination.DEFAULT_PAGE_SIZE) long pageSize,
            @RequestHeader(value = RequestIdFilter.HEADER, required = false) String requestId) {
        scopeGuard.requireExistingContest(contestId);
        return ResponseEntity.ok(ApiResponse.success(requestId,
                riskAnalyticsService.students(contestId, page, pageSize)));
    }

    private static ContestSnapshotCandidateView findCandidate(ContestSnapshotView snapshot, String studentId) {
        List<ContestSnapshotCandidateView> candidates = snapshot.candidates();
        if (candidates == null) {
            return null;
        }
        return candidates.stream()
                .filter(candidate -> studentId.equals(candidate.studentId()))
                .findFirst()
                .orElse(null);
    }

    /** 可选时间参数：缺省或空白视为"不限"；写了但不是 ISO-8601 就是区间参数不合法。 */
    private static Instant parseOptionalInstant(String value, String parameterName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new AnalyticsException.InvalidRange(parameterName + " 不是合法的 ISO-8601 时间：" + value);
        }
    }

    private static void requireOrderedRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new AnalyticsException.InvalidRange("from 不能晚于 to");
        }
    }
}
