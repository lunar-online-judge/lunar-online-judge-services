package io.lunar.analytics.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 参照数据刷新的节奏（{@code analytics.refresh.*}）。
 *
 * 为什么要区分"最长陈旧"与"活跃窗口"：前者决定**请求路径上**要不要顺带刷新（它是用户等待的一部分，
 * 因此要短），后者决定**定时任务**去刷哪些考试（它是后台成本，因此可以宽）。把两者合成一个值，
 * 要么让用户按分钟级承担上游抖动，要么让刚考完的考试在参照数据上长期失准。
 */
@ConfigurationProperties("analytics.refresh")
public class RefreshProperties {

    /** 请求路径上容忍的最大陈旧时间；超过它就顺带刷新一次。 */
    private Duration maxAge = Duration.ofMinutes(5);

    /** 定时刷新的周期（只刷最近有事实写入的考试）。 */
    private long intervalMs = 300_000L;

    /** "最近活跃"的时间窗：超出它的考试不再被定时任务刷新。 */
    private Duration activeWindow = Duration.ofMinutes(30);

    public Duration getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(Duration maxAge) {
        this.maxAge = maxAge;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public Duration getActiveWindow() {
        return activeWindow;
    }

    public void setActiveWindow(Duration activeWindow) {
        this.activeWindow = activeWindow;
    }
}
