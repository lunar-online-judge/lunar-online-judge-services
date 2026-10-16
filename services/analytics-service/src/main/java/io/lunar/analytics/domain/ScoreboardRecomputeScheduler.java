package io.lunar.analytics.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 成绩读模型的定时增量重算触发点。
 *
 * 为什么必须有它：读路径的自愈只在"读模型为空"时生效（见 ScoreboardService#ensureComputed），
 * 一场正在进行的考试第一次被查询之后表里就有行了——没有定时重算，榜单会一直停在第一次算出来的那一刻。
 *
 * 为什么与 ContestRefRefreshScheduler 分成两个 Bean：两者周期不同（参照数据可以刷得勤，
 * 重算是整场聚合 + 整批替换），且各自的失败不应影响对方。
 *
 * 与它一样，这里也必须兜住异常：调度线程抛出 RuntimeException 会让 Spring 取消该任务的排期，
 * 一次数据库抖动就会永久停掉重算，而且之后不再有任何日志。
 */
@Component
public class ScoreboardRecomputeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScoreboardRecomputeScheduler.class);

    private final AnalyticsRebuildService analyticsRebuildService;

    public ScoreboardRecomputeScheduler(AnalyticsRebuildService analyticsRebuildService) {
        this.analyticsRebuildService = analyticsRebuildService;
    }

    @Scheduled(initialDelayString = "${analytics.recompute.interval-ms:600000}",
            fixedDelayString = "${analytics.recompute.interval-ms:600000}")
    public void rebuildStaleScoreboards() {
        try {
            analyticsRebuildService.rebuildStaleContests();
        } catch (RuntimeException exception) {
            log.error("成绩增量重算整体失败，等待下一轮", exception);
        }
    }
}
