package io.lunar.ai.domain;

import io.lunar.ai.infra.ContestInternalApi;
import io.lunar.ai.infra.dto.ContestSnapshotView;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.identity.ServiceTokenProvider;
import io.lunar.common.security.AccessScope;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * AI 能力的资源范围门禁（BACKEND_IMPROVEMENT_PLAN BE-01）。
 *
 * 为什么 AI 侧要自己判：AI 结论绑定的是"某场考试的某位考生的某次提交"，
 * 而考试归属是 contest-service 的数据。取回 ownerTeacherId 后用 {@link AccessScope} 判断，
 * 就能让**老师用上 AI**，而不是像风险事件查询那样只能先收紧为 ADMIN-only。
 *
 * 越权与不存在返回同一个 404（{@link AiException.ResourceNotFound}）：区分它们等于确认"这个考试存在"。
 * 身份只来自已验证令牌（AccessScope 从 authorities/sub 派生），不取任何请求参数（BE-01 明确要求）。
 */
@Component
public class AiScopeGuard {

    private final ContestInternalApi contestApi;
    private final ServiceTokenProvider serviceTokenProvider;

    public AiScopeGuard(ContestInternalApi contestApi, ServiceTokenProvider serviceTokenProvider) {
        this.contestApi = contestApi;
        this.serviceTokenProvider = serviceTokenProvider;
    }

    /** 要求调用者有权对指定考试使用 AI：ADMIN 全通过，TEACHER 仅限自己拥有的考试。 */
    public void requireContestAccess(String contestId, AccessScope scope) {
        if (contestId == null || contestId.isBlank()) {
            throw new AiException.InvalidRequest("缺少 contestId");
        }
        ContestSnapshotView snapshot = fetchSnapshot(contestId);
        if (snapshot == null || !scope.allows(snapshot.ownerTeacherId())) {
            throw new AiException.ResourceNotFound();
        }
    }

    /** 资源不带考试归属（如未绑定考试的出题草稿）时，只有 ADMIN 能用。 */
    public void requireGlobalAccess(AccessScope scope) {
        if (!scope.global()) {
            throw new AiException.ResourceNotFound();
        }
    }

    private ContestSnapshotView fetchSnapshot(String contestId) {
        try {
            ApiResponse<ContestSnapshotView> response = contestApi.snapshot(contestId,
                    "Bearer " + serviceTokenProvider.token());
            return response == null ? null : response.data();
        } catch (HttpClientErrorException.NotFound exception) {
            // 上游 404 与"不归你管"对外必须无法区分
            return null;
        }
    }
}
