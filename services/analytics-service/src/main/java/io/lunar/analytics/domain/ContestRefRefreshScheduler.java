package io.lunar.analytics.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 参照数据的定时刷新触发点。
 *
 * 为什么把 {@code @Scheduled} 单独放一个 Bean，而不是加在 ContestRefRefreshService 上：
 * 编排方法（本类调用的那个）必须通过 Spring 代理进入，否则 {@code @Transactional} 不生效——
 * 定时方法直接调本类的另一个方法是自调用，事务注解会被静默忽略，而"先删后插"失去事务
 * 意味着参照表可能出现空窗。分成两个 Bean 之后调用天然过代理。
 *
 * 周期直接引用 {@code analytics.refresh.interval-ms}（与 RefreshProperties 同一个键），
 * 首次执行延迟同样取值：应用刚起来时数据库可能还在被别的实例迁移，没必要立刻打上游。
 */
@Component
public class ContestRefRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContestRefRefreshScheduler.class);

    private final ContestRefRefreshService contestRefRefreshService;

    public ContestRefRefreshScheduler(ContestRefRefreshService contestRefRefreshService) {
        this.contestRefRefreshService = contestRefRefreshService;
    }

    @Scheduled(initialDelayString = "${analytics.refresh.interval-ms:300000}",
            fixedDelayString = "${analytics.refresh.interval-ms:300000}")
    public void refreshActiveContestRefs() {
        try {
            contestRefRefreshService.refreshActiveContests();
        } catch (RuntimeException exception) {
            // 调度线程抛异常会让后续执行被取消（Spring 会取消该任务的排期）：这里必须兜住，
            // 否则一次数据库抖动就永久停掉参照数据刷新，且不再有任何日志
            log.error("参照数据定时刷新整体失败，等待下一轮", exception);
        }
    }
}
