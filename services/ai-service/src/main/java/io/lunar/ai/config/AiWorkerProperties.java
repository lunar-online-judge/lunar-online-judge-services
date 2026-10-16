package io.lunar.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 作业执行器参数（dataId {@code ai-service.yml}，Nacos 托管）。 */
@ConfigurationProperties("ai.worker")
public class AiWorkerProperties {

    /** 同时执行的作业数上限：LLM 是慢且贵的资源，并发要显式限制，不能随请求量线性放大。 */
    private int concurrency = 2;

    /** 租约时长（秒）：领取后超过它仍未完成，视为执行方已死，作业可被重新领取。 */
    private int leaseSeconds = 300;

    /** 单个作业的最大尝试次数，超过转 FAILED 并记录错误码。 */
    private int maxAttempts = 3;

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
