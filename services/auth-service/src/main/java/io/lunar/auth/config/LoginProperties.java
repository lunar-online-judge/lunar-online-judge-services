package io.lunar.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * 登录限流参数：放在 Nacos 配置中心（docs/middleware.md"配置中心"节），改值后无需重启即可生效。
 *
 * 刻意用可变 JavaBean 而不是 record：热更新依赖 ConfigurationPropertiesRebinder 就地重绑同一个实例，
 * 持有该引用的 {@link io.lunar.auth.domain.LoginRateLimiter} 才能在下一次调用读到新值；
 * record 属构造器绑定，重绑会换实例，已注入的引用仍指向旧值。
 */
@ConfigurationProperties("auth.login")
public class LoginProperties {

    /** 连续失败达到该次数即锁定。 */
    private int maxFailures = 5;

    /** 锁定时长，同时作为失败计数的窗口长度；数字按分钟解释（兼容原 lock-minutes 语义）。 */
    @DurationUnit(ChronoUnit.MINUTES)
    private Duration lockMinutes = Duration.ofMinutes(5);

    public int getMaxFailures() { return maxFailures; }
    public void setMaxFailures(int maxFailures) { this.maxFailures = maxFailures; }
    public Duration getLockMinutes() { return lockMinutes; }
    public void setLockMinutes(Duration lockMinutes) { this.lockMinutes = lockMinutes; }
}
