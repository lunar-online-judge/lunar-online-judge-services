package io.lunar.auth.domain;

import io.lunar.auth.config.LoginProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配守卫：LoginRateLimiter 的依赖来自 LoginProperties（原先由 @Value 注入），
 * 一旦注入点配错，应用会在启动时失败而不是测试时（GoJudgeAdapter 与本次 LoginRateLimiter 的
 * 两个构造器都栽过同类坑）。这里走一遍 Spring 的构造器注入与属性绑定路径，把这类错误拦在测试阶段。
 */
class LoginRateLimiterContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(StringRedisTemplate.class, () -> Mockito.mock(StringRedisTemplate.class))
            .withUserConfiguration(Config.class);

    @Test
    void springResolvesConstructorInjection() {
        runner.run(context -> assertThat(context).hasSingleBean(LoginRateLimiter.class));
    }

    @Test
    void propertiesBindFromEnvironment() {
        runner.withPropertyValues("auth.login.max-failures=7", "auth.login.lock-minutes=3")
                .run(context -> {
                    LoginProperties properties = context.getBean(LoginProperties.class);
                    assertThat(properties.getMaxFailures()).isEqualTo(7);
                    // @DurationUnit(MINUTES)：数字 3 必须解释为 3 分钟。
                    // 若误按毫秒绑定，锁定会静默变成 3 毫秒——功能看着正常实则失效，所以这里钉死单位
                    assertThat(properties.getLockMinutes()).isEqualTo(Duration.ofMinutes(3));
                });
    }

    @Configuration
    @EnableConfigurationProperties(LoginProperties.class)
    @Import(LoginRateLimiter.class)
    static class Config {
    }
}
