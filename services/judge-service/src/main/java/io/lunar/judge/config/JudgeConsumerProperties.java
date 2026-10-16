package io.lunar.judge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消费者去重参数（dataId {@code judge-service.yml}，见 docs/middleware.md"配置中心"节）。
 *
 * 用 {@code @ConfigurationProperties} 而不是 {@code @Value}：只有"可变 JavaBean + 每次调用时读取"
 * 才能热更新，构造期读进字段等于把值固化（那样配置中心就只剩"改完重启"的价值）。
 */
@ConfigurationProperties("judge.consumer")
public class JudgeConsumerProperties {

    /** 已处理事件的登记保留时长（小时）：必须覆盖 RocketMQ 的最大重投窗口。 */
    private int consumedEventTtlHours = 24;

    public int getConsumedEventTtlHours() {
        return consumedEventTtlHours;
    }

    public void setConsumedEventTtlHours(int consumedEventTtlHours) {
        this.consumedEventTtlHours = consumedEventTtlHours;
    }
}
