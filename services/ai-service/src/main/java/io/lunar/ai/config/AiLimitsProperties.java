package io.lunar.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配额与开关（dataId {@code ai-service.yml}，Nacos 托管）。
 *
 * 放在一起而不是拆成三份：它们都是"限制与开关"，改一个往往要连带看另一个（例如开启 embedding
 * 之前先确认配额与阈值），拆开反而要在三个文件之间跳。
 */
@ConfigurationProperties("ai.limits")
public class AiLimitsProperties {

    /** 每个调用者每日可提交的作业数上限：防止一次误操作把 Provider 配额烧穿。 */
    private int maxJobsPerDay = 200;

    public int getMaxJobsPerDay() {
        return maxJobsPerDay;
    }

    public void setMaxJobsPerDay(int maxJobsPerDay) {
        this.maxJobsPerDay = maxJobsPerDay;
    }
}
