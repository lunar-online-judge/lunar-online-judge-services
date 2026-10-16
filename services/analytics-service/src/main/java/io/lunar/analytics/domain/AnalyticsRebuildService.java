package io.lunar.analytics.domain;

import io.lunar.analytics.domain.dto.RebuildResult;
import io.lunar.analytics.infra.SubmissionFactMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重算的单飞闸门与增量调度。
 *
 * 为什么必须单飞：重算是"整批删除 + 整批写入"。两次重算并发跑时会互相覆盖——后提交的那次
 * 用自己的聚合结果盖掉前一次的，如果两次读到的提交事实快照不同（重算期间有新的判决到达），
 * 最终落在表里的名次既不是第一次的也不是第二次的，而是两者的混合，且没有任何痕迹。
 *
 * 代价与边界：闸门是**进程内**的（ConcurrentHashMap + 单实例语义）。多实例部署时两个实例仍可能同时重算，
 * 真要跨实例互斥得用数据库咨询锁或分布式锁——这属于本轮未实现的部分，已在报告中列为待确认。
 */
@Service
public class AnalyticsRebuildService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRebuildService.class);

    /**
     * 读路径自愈的冷却时间。
     *
     * 没有它会出现这种病态：一场还没有任何提交的考试，榜单接口每次被轮询都会走一次
     * "聚合 + 整表删除"（表里始终没有行，于是永远判定为"需要重算"），把数据库刷出一堆死元组。
     * 冷却期内直接跳过自愈，读到的空榜单本来就是正确答案。
     */
    private static final Duration SELF_HEAL_COOLDOWN = Duration.ofSeconds(30);

    private final ScoreboardRebuildService scoreboardRebuildService;
    private final SubmissionFactMapper submissionFactMapper;
    private final RecomputeProperties recomputeProperties;
    private final Set<String> rebuildsInProgress = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> lastAttemptAtByContest = new ConcurrentHashMap<>();

    public AnalyticsRebuildService(ScoreboardRebuildService scoreboardRebuildService,
                                   SubmissionFactMapper submissionFactMapper,
                                   RecomputeProperties recomputeProperties) {
        this.scoreboardRebuildService = scoreboardRebuildService;
        this.submissionFactMapper = submissionFactMapper;
        this.recomputeProperties = recomputeProperties;
    }

    /** 显式重算：进行中直接 409，让调用方知道"你要的事正在做"，而不是静默排队或重复劳动。 */
    public RebuildResult rebuild(String contestId) {
        if (!rebuildsInProgress.add(contestId)) {
            throw new AnalyticsException.RebuildInProgress(contestId);
        }
        lastAttemptAtByContest.put(contestId, Instant.now());
        try {
            return scoreboardRebuildService.rebuild(contestId);
        } finally {
            rebuildsInProgress.remove(contestId);
        }
    }

    /**
     * 读路径上的自愈重算：只有在没人算、且冷却期已过时才算，且**不抛 409**。
     *
     * 读接口不该因为"另一个请求正在重算"而失败——那会让榜单在重算窗口内随机 409；
     * 返回 null 表示"这次没算，读到的可能是空/旧数据"，由调用方继续按读模型返回。
     */
    public RebuildResult rebuildIfIdle(String contestId) {
        if (isCoolingDown(contestId) || !rebuildsInProgress.add(contestId)) {
            return null;
        }
        lastAttemptAtByContest.put(contestId, Instant.now());
        try {
            return scoreboardRebuildService.rebuild(contestId);
        } finally {
            rebuildsInProgress.remove(contestId);
        }
    }

    private boolean isCoolingDown(String contestId) {
        Instant lastAttemptAt = lastAttemptAtByContest.get(contestId);
        return lastAttemptAt != null && lastAttemptAt.isAfter(Instant.now().minus(SELF_HEAL_COOLDOWN));
    }

    /**
     * 定时增量重算：只算"读模型落后于事实"的考试，返回实际重算的数量。
     *
     * 为什么是增量而不是全量：正在进行的考试会持续产生新判决，全量重算等于每轮把所有考试算一遍；
     * 这里的候选来自 SQL 里 {@code computed_at < judged_at} 的比较（见 SubmissionFactMapper），
     * 一场没有新判决的考试根本不会进入候选。
     *
     * 单个考试失败不中断整批（catch + warn）：批量任务尽力而为，一场考试的异常不该让其余几十场一起停更。
     */
    public int rebuildStaleContests() {
        Instant since = Instant.now().minus(recomputeProperties.getWindow());
        List<String> contestIds = submissionFactMapper.selectContestIdsNeedingScoreRebuild(
                since, recomputeProperties.getMaxContestsPerRun());
        int rebuiltCount = 0;
        for (String contestId : contestIds) {
            try {
                if (rebuildIfIdle(contestId) != null) {
                    rebuiltCount++;
                }
            } catch (RuntimeException exception) {
                log.warn("成绩重算失败，本轮跳过（下一轮重试）：contestId={}", contestId, exception);
            }
        }
        if (rebuiltCount > 0) {
            log.info("成绩增量重算完成：候选 {} 场，成功 {} 场", contestIds.size(), rebuiltCount);
        }
        return rebuiltCount;
    }
}
