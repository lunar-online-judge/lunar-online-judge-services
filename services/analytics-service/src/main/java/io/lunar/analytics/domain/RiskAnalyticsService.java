package io.lunar.analytics.domain;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.lunar.analytics.domain.dto.AnalyticsPage;
import io.lunar.analytics.domain.dto.LabeledCount;
import io.lunar.analytics.domain.dto.RiskHeatCell;
import io.lunar.analytics.domain.dto.RiskStudentRow;
import io.lunar.analytics.domain.dto.RiskStudentView;
import io.lunar.analytics.domain.dto.RiskSummaryCounts;
import io.lunar.analytics.domain.dto.RiskSummaryView;
import io.lunar.analytics.infra.ContestCandidateRefEntity;
import io.lunar.analytics.infra.ContestCandidateRefMapper;
import io.lunar.analytics.infra.RiskEventFactMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监考风险分析：按需聚合风险事件事实。
 *
 * 为什么风险聚合不物化：风险事件的数量级远小于提交，而审核状态会被人工反复流转
 * （OPEN→ACKNOWLEDGED→CONFIRMED/DISMISSED），物化它就得跟着状态机同步失效——
 * 一处漏掉，监考看到的待办数就是错的。
 *
 * AI 结论曾经作为"证据"并入这里，按简化决定已整体下线（ai-service 不再回写 findings 事件，
 * analytics 不再保留 ai_finding_facts 读模型）：模型输出可能被提示注入影响，
 * 把它混进监考面板既增加耦合又容易让人误以为它是判据。
 */
@Service
public class RiskAnalyticsService {

    private static final int SECONDS_PER_MINUTE = 60;

    private final RiskEventFactMapper riskEventFactMapper;
    private final ContestCandidateRefMapper contestCandidateRefMapper;
    private final RiskAnalyticsProperties riskProperties;

    public RiskAnalyticsService(RiskEventFactMapper riskEventFactMapper,
                                ContestCandidateRefMapper contestCandidateRefMapper,
                                RiskAnalyticsProperties riskProperties) {
        this.riskEventFactMapper = riskEventFactMapper;
        this.contestCandidateRefMapper = contestCandidateRefMapper;
        this.riskProperties = riskProperties;
    }

    /** 汇总：标量计数 + 三个分布 + 热力图。 */
    public RiskSummaryView summary(String contestId) {
        RiskSummaryCounts counts = riskEventFactMapper.selectRiskSummaryCounts(contestId);
        List<LabeledCount> bySeverity = riskEventFactMapper.selectRiskCountsBySeverity(contestId);
        List<LabeledCount> byEventType = riskEventFactMapper.selectRiskCountsByEventType(contestId);
        List<LabeledCount> byReviewStatus = riskEventFactMapper.selectRiskCountsByReviewStatus(contestId);
        List<RiskHeatCell> heatmap = riskEventFactMapper.selectRiskHeatmap(contestId,
                heatmapBucketSeconds(), riskProperties.getHeatmapMaxBuckets());
        return new RiskSummaryView(counts.totalEvents(), counts.pendingReview(), counts.confirmedCount(),
                counts.dismissedCount(), counts.studentsWithRisk(),
                bySeverity, byEventType, byReviewStatus, heatmap);
    }

    /**
     * 风险学生分页（可疑度降序）。
     *
     * 名单按"当前页的学生集合"批量取：逐行查会变成 N+1，而这里的分页大小上限是 100，批量取的规模可控。
     */
    public AnalyticsPage<RiskStudentView> students(String contestId, long page, long pageSize) {
        AnalyticsPageRequest pageRequest = AnalyticsPageRequest.of(page, pageSize);
        List<RiskStudentRow> rows = riskEventFactMapper.selectRiskStudents(contestId,
                riskProperties.getWeightHigh(), riskProperties.getWeightConfirmed(),
                riskProperties.getWeightAcknowledged(), riskProperties.getWeightPending(),
                pageRequest.pageSize(), pageRequest.offset());
        int total = riskEventFactMapper.countRiskStudents(contestId);
        List<String> studentIds = rows.stream().map(RiskStudentRow::studentId).toList();
        Map<String, ContestCandidateRefEntity> candidates = loadCandidates(contestId, studentIds);

        List<RiskStudentView> items = new ArrayList<>(rows.size());
        for (RiskStudentRow row : rows) {
            ContestCandidateRefEntity candidate = candidates.get(row.studentId());
            items.add(new RiskStudentView(row.studentId(),
                    candidate == null ? null : candidate.getStudentNo(),
                    candidate == null ? null : candidate.getDisplayName(),
                    row.riskScore(), row.eventCount(), row.highSeverityCount(), row.confirmedCount(),
                    row.pendingCount(), row.latestOccurredAt()));
        }
        return new AnalyticsPage<>(items, total, pageRequest.page(), pageRequest.pageSize());
    }

    /** 参照数据里的学号/姓名；缺了就返回空表（能力上不能因为参照数据没刷新而查不出风险）。 */
    private Map<String, ContestCandidateRefEntity> loadCandidates(String contestId, List<String> studentIds) {
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        List<ContestCandidateRefEntity> candidates =
                contestCandidateRefMapper.selectList(new LambdaQueryWrapper<ContestCandidateRefEntity>()
                        .eq(ContestCandidateRefEntity::getContestId, contestId)
                        .in(ContestCandidateRefEntity::getStudentId, studentIds));
        Map<String, ContestCandidateRefEntity> candidatesById = new LinkedHashMap<>();
        for (ContestCandidateRefEntity candidate : candidates) {
            // 同一 (contest_id, student_id) 是主键，不会真的重复；用 putIfAbsent 只是不假设上游行为
            candidatesById.putIfAbsent(candidate.getStudentId(), candidate);
        }
        return candidatesById;
    }

    private int heatmapBucketSeconds() {
        return Math.multiplyExact(riskProperties.getHeatmapBucketMinutes(), SECONDS_PER_MINUTE);
    }
}
