package io.lunar.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 分析读模型服务入口。
 *
 * 为什么需要定时能力：本服务是纯投影侧，事实表只从 MQ 事件增量累积，而 contest_refs /
 * contest_problem_refs / contest_candidate_refs 这些参照数据没有任何事件来源，只能由定时任务
 * 从 contest / problem 的服务间接口定期拉取（下一轮实现）。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
