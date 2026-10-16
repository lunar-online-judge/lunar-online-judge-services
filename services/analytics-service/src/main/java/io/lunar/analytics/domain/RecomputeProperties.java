package io.lunar.analytics.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 成绩读模型重算的节奏（{@code analytics.recompute.*}）。
 *
 * 为什么要与参照数据刷新分开配：两者关心的东西不同——参照数据刷新看"最近有没有事实写入"，
 * 重算要看"读模型有没有落后于事实"。若共用一个开关，调快重算会顺带把上游快照拉取也调快。
 */
@ConfigurationProperties("analytics.recompute")
public class RecomputeProperties {

    /**
     * 定时重算的周期。
     *
     * 这个值决定"榜单最多落后多久"：重算本身是整场考试的聚合 + 整批替换，
     * 跑得太勤会与正在进行的考试抢数据库，跑得太慢则老师看到的排名明显滞后。
     */
    private long intervalMs = 600_000L;

    /** 只看这个窗口内有过判决的考试：更早的判决不会让"读模型落后"这件事继续变化。 */
    private Duration window = Duration.ofMinutes(30);

    /** 单轮最多重算的考试数：防止历史回填时一轮把所有考试一起算，把数据库打满。 */
    private int maxContestsPerRun = 50;

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public int getMaxContestsPerRun() {
        return maxContestsPerRun;
    }

    public void setMaxContestsPerRun(int maxContestsPerRun) {
        this.maxContestsPerRun = maxContestsPerRun;
    }
}
