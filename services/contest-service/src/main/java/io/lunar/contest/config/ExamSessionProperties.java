package io.lunar.contest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 考试会话参数：放在 Nacos 配置中心（docs/middleware.md"配置中心"节），改值后无需重启即可生效
 * （考生网络抖动时可临时放宽心跳超时，不必重启 contest-service）。
 *
 * 用可变 JavaBean：热更新依赖就地重绑同一个实例，持有该引用的 ExamSessionService 才能读到新值。
 */
@ConfigurationProperties("exam.session")
public class ExamSessionProperties {

    /** 心跳超时（分钟）：超过该时长未心跳的 ACTIVE 会话被判为 EXPIRED。 */
    private long heartbeatTimeoutMinutes = 2;

    public long getHeartbeatTimeoutMinutes() { return heartbeatTimeoutMinutes; }
    public void setHeartbeatTimeoutMinutes(long heartbeatTimeoutMinutes) {
        this.heartbeatTimeoutMinutes = heartbeatTimeoutMinutes;
    }
}
