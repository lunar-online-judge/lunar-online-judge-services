package io.lunar.auth.domain;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配守卫：LoginRateLimiter 另有一个包级构造器供单测传短 TTL，
 * 若容器装配入口漏标 @Autowired，Spring 会退化成寻找默认构造器并让应用启动失败。
 * 单测直接 new 对象发现不了这个问题（GoJudgeAdapter 就栽过同一坑），
 * 因此这里走一遍 Spring 的构造器注入路径，把这类错误拦在测试阶段。
 */
class LoginRateLimiterContextTest {

    @Test
    void springResolvesConstructorInjection() {
        new ApplicationContextRunner()
                .withBean(StringRedisTemplate.class, () -> Mockito.mock(StringRedisTemplate.class))
                .withUserConfiguration(Config.class)
                .run(context -> assertThat(context).hasSingleBean(LoginRateLimiter.class));
    }

    @Configuration
    @Import(LoginRateLimiter.class)
    static class Config {

        /** @Value 占位符（默认值）需要它才能解析。 */
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }
}
