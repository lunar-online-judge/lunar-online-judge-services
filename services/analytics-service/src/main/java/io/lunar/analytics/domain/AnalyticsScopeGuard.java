package io.lunar.analytics.domain;

import io.lunar.analytics.infra.dto.ContestSnapshotCandidateView;
import io.lunar.analytics.infra.dto.ContestSnapshotView;
import io.lunar.common.security.AccessScope;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分析读模型的资源范围门禁（BACKEND_IMPROVEMENT_PLAN BE-01）。
 *
 * 为什么分析侧要自己判：分析查询的入参只有 contestId，而"这场考试归谁"住在 contest-service。
 * 取回 {@code ownerTeacherId} 后用 {@link AccessScope} 判断，才能让**老师看到自己考试的数据**，
 * 而不是像早期的风险事件查询那样整片收紧为 ADMIN-only。
 *
 * 越权与不存在返回同一个 404（{@link AnalyticsException.ResourceNotFound}）：区分它们等于确认
 * "这场考试存在"。身份只来自已验证令牌（AccessScope 从 authorities/sub 派生），不取任何请求参数。
 *
 * 返回值是快照本身而不是 void：鉴权必然要先取一次快照，而参照数据刷新正需要它——
 * 让门禁把它交出来，一个请求就只需要一次上游往返。
 */
@Component
public class AnalyticsScopeGuard {

    /** 考生能看自己成绩的前提是"仍在名单里"（移出名单会同时失效会话，见 docs/exam-process.md §9）。 */
    private static final String ENROLLED_STATUS = "ENROLLED";

    private final ContestRefRefreshService contestRefRefreshService;

    public AnalyticsScopeGuard(ContestRefRefreshService contestRefRefreshService) {
        this.contestRefRefreshService = contestRefRefreshService;
    }

    /**
     * 要求调用者有权查看指定考试的分析数据：ADMIN 全通过，TEACHER 仅限自己拥有的考试。
     *
     * 顺带完成该考试的参照数据按需刷新（缓存新鲜时不做任何写操作）。
     */
    public ContestSnapshotView requireContestAccess(String contestId, AccessScope scope) {
        ContestSnapshotView snapshot = loadSnapshot(contestId);
        if (!scope.allows(snapshot.ownerTeacherId())) {
            throw new AnalyticsException.ResourceNotFound();
        }
        return snapshot;
    }

    /**
     * 要求调用者是该考试的在册考生，返回快照（考生只能看自己那一行，因此不接收 studentId 参数，
     * 身份一律取令牌的 sub——接收参数就等于允许越权查询）。
     */
    public ContestSnapshotView requireCandidateAccess(String contestId, Authentication authentication) {
        ContestSnapshotView snapshot = loadSnapshot(contestId);
        String candidateId = authentication.getName();
        if (!isEnrolled(snapshot, candidateId)) {
            throw new AnalyticsException.ResourceNotFound();
        }
        return snapshot;
    }

    /** 只读快照、不判范围：ADMIN 专用接口（风险证据）用它触发参照数据刷新。 */
    public ContestSnapshotView requireExistingContest(String contestId) {
        return loadSnapshot(contestId);
    }

    private ContestSnapshotView loadSnapshot(String contestId) {
        Identifiers.requireUuid(contestId);
        ContestSnapshotView snapshot = contestRefRefreshService.fetchSnapshot(contestId);
        if (snapshot == null) {
            throw new AnalyticsException.ResourceNotFound();
        }
        contestRefRefreshService.ensureFresh(snapshot);
        return snapshot;
    }

    private static boolean isEnrolled(ContestSnapshotView snapshot, String candidateId) {
        List<ContestSnapshotCandidateView> candidates = snapshot.candidates();
        if (candidates == null) {
            return false;
        }
        return candidates.stream()
                .anyMatch(candidate -> ENROLLED_STATUS.equals(candidate.enrollmentStatus())
                        && candidateId.equals(candidate.studentId()));
    }
}
