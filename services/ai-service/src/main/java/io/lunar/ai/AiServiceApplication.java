package io.lunar.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 能力服务（docs/services.md 的 ai-service 节）。
 *
 * 定位：**产证据，不改事实**。它读取提交/风险事件等既有事实，产出 AI finding（代码评审、
 * 风险研判、相似度、出题草稿），永不写成绩、永不流转审核状态、永不直接发布题目。
 *
 * {@code @EnableScheduling}：作业执行器（{@code AiJobWorker}）与 Outbox 发布器都靠它驱动。
 * {@code @ConfigurationPropertiesScan}：让 config 包下的可变参数（Provider、并发、配额）交给 Nacos 托管。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class AiServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
