package io.lunar.analytics.domain;

import io.lunar.analytics.infra.ContestInternalApi;
import io.lunar.analytics.infra.RiskEventFactMapper;
import io.lunar.analytics.infra.SubmissionFactMapper;
import io.lunar.analytics.infra.dto.ContestSnapshotView;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.identity.ServiceTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 参照数据的取数与刷新编排：从 contest-service 拉考试快照，交给 {@link ContestRefStore} 落库。
 *
 * 两条触发路径，覆盖的失效场景不同：
 * ① **按需**（{@link #ensureFresh}）：请求路径上发现缓存过期就顺带刷新——它解决"刚创建/刚改动的考试
 *    立刻要能被查到"，代价是让某个请求多等一次上游往返，所以只在超过 {@code analytics.refresh.max-age} 时发生；
 * ② **定时**（{@link #refreshActiveContests}）：只刷"最近有事实写入"或"参照数据完全缺失"的考试——
 *    不做全表轮询，因为历史竞赛的参照数据不会变，刷它们是纯粹的资源浪费。
 *
 * HTTP 一律在事务外（写库集中在 ContestRefStore）：上游慢不应该占着数据库连接。
 */
@Service
public class ContestRefRefreshService {

    private static final Logger log = LoggerFactory.getLogger(ContestRefRefreshService.class);

    /** 单轮定时刷新最多处理的考试数：防止历史数据首次回填时一轮打满上游。 */
    private static final int MAX_CONTESTS_PER_RUN = 100;

    private final ContestInternalApi contestApi;
    private final ServiceTokenProvider serviceTokenProvider;
    private final ContestRefStore contestRefStore;
    private final SubmissionFactMapper submissionFactMapper;
    private final RiskEventFactMapper riskEventFactMapper;
    private final RefreshProperties refreshProperties;

    public ContestRefRefreshService(ContestInternalApi contestApi,
                                    ServiceTokenProvider serviceTokenProvider,
                                    ContestRefStore contestRefStore,
                                    SubmissionFactMapper submissionFactMapper,
                                    RiskEventFactMapper riskEventFactMapper,
                                    RefreshProperties refreshProperties) {
        this.contestApi = contestApi;
        this.serviceTokenProvider = serviceTokenProvider;
        this.contestRefStore = contestRefStore;
        this.submissionFactMapper = submissionFactMapper;
        this.riskEventFactMapper = riskEventFactMapper;
        this.refreshProperties = refreshProperties;
    }

    /**
     * 取一份考试快照（服务身份令牌；该端点是 {@code roles=["SERVICE"]}）。
     *
     * 上游 404 返回 null（"这场考试不存在"），其余故障原样抛出：401/503 是基础设施故障，
     * 把它也当成 404 会让"服务令牌没配好"表现为"所有考试都查不到"，把配置问题伪装成数据问题。
     */
    public ContestSnapshotView fetchSnapshot(String contestId) {
        try {
            ApiResponse<ContestSnapshotView> response =
                    contestApi.snapshot(contestId, "Bearer " + serviceTokenProvider.token());
            return response == null ? null : response.data();
        } catch (HttpClientErrorException.NotFound exception) {
            log.debug("考试快照不存在：contestId={}", contestId);
            return null;
        }
    }

    /**
     * 请求路径上的按需刷新：缓存仍新鲜就什么都不做。
     *
     * 快照由调用方（ScopeGuard）已经取过一次并用于鉴权，这里不重复取数。
     */
    public void ensureFresh(ContestSnapshotView snapshot) {
        if (contestRefStore.isFresh(snapshot.contestId(), refreshProperties.getMaxAge().toSeconds())) {
            return;
        }
        contestRefStore.apply(snapshot);
    }

    /** 拉取并强制刷新一个考试；返回是否拿到了快照（false = 上游说这场考试不存在）。 */
    public boolean refresh(String contestId) {
        ContestSnapshotView snapshot = fetchSnapshot(contestId);
        if (snapshot == null) {
            return false;
        }
        contestRefStore.apply(snapshot);
        return true;
    }

    /**
     * 定时任务的入口：刷新所有"需要被刷新"的考试，返回实际刷新的数量。
     *
     * 单个考试失败不中断整批（catch + warn）：批量任务的语义是尽力而为，
     * 让一场考试的上游异常把其余几十场一起拖垮，只会让参照数据整体停更。
     */
    public int refreshActiveContests() {
        Set<String> contestIds = collectContestsNeedingRefresh();
        int refreshedCount = 0;
        for (String contestId : contestIds) {
            try {
                if (refresh(contestId)) {
                    refreshedCount++;
                }
            } catch (RuntimeException exception) {
                log.warn("参照数据刷新失败，本轮跳过（下一轮重试）：contestId={}", contestId, exception);
            }
        }
        if (refreshedCount > 0) {
            log.info("参照数据定时刷新完成：候选 {} 场，成功 {} 场", contestIds.size(), refreshedCount);
        }
        return refreshedCount;
    }

    /**
     * 需要刷新的考试 = 最近有判决的 ∪ 最近有风险事件的 ∪ 参照数据完全缺失的（去重后截断）。
     *
     * 前两项保证"正在进行的考试"数据新鲜，第三项保证"早已结束、但参照数据从没拉过"的考试能自愈。
     */
    private Set<String> collectContestsNeedingRefresh() {
        Instant activeSince = Instant.now().minus(refreshProperties.getActiveWindow());
        Set<String> contestIds = new LinkedHashSet<>();
        contestIds.addAll(submissionFactMapper.selectContestIdsJudgedSince(activeSince));
        contestIds.addAll(riskEventFactMapper.selectContestIdsOccurredSince(activeSince));
        contestIds.addAll(submissionFactMapper.selectContestIdsMissingRefs(MAX_CONTESTS_PER_RUN));
        if (contestIds.size() <= MAX_CONTESTS_PER_RUN) {
            return contestIds;
        }
        Set<String> limited = new LinkedHashSet<>();
        for (String contestId : contestIds) {
            if (limited.size() >= MAX_CONTESTS_PER_RUN) {
                break;
            }
            limited.add(contestId);
        }
        return limited;
    }
}
