package io.lunar.problem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 测试点服务间传输参数（docs/middleware.md go-judge 节）：
 * 测试点随 submission.created 事件送到评测引擎，因此必须有体积上限——
 * 超过 MQ 单条上限的消息永远发不出去，Outbox 会每轮重试（真机上表现为判题结果永不回来）。
 * 大测试点走对象存储是文档已留的演进方向。
 */
@ConfigurationProperties("problem.testdata")
public class TestdataProperties {

    /** 单次服务间返回的测试点总字节上限（默认 1 MiB，可用 PROBLEM_TESTDATA_MAX_BYTES 覆盖）。 */
    private long maxBytes = 1024 * 1024;

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }
}
