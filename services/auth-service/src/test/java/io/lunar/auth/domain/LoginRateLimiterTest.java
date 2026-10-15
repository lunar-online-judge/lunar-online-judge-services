package io.lunar.auth.domain;

import io.lunar.auth.config.LoginProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 对真实 Redis 跑：TTL 过期、多实例共享这两条关键语义由服务端控制，假时钟覆盖不了，因此不 mock。
 * 本机 Compose 提供 Redis（docs/deployment.md）；Redis 不可达时整体跳过而不是让测试失败。
 * 用例用短锁定时长（数百毫秒）避免等待。
 */
class LoginRateLimiterTest {

    private static final String HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

    private static StringRedisTemplate redis;
    private static boolean redisReachable;

    private final List<String> usedUsernames = new ArrayList<>();

    @BeforeAll
    static void connect() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(HOST, PORT));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        try {
            redis.getConnectionFactory().getConnection().ping();
            redisReachable = true;
        } catch (Exception exception) {
            redisReachable = false;
        }
    }

    @AfterEach
    void cleanup() {
        if (redisReachable) {
            usedUsernames.forEach(username -> redis.delete(
                    List.of("auth:login:fail:" + username, "auth:login:lock:" + username)));
        }
    }

    @Test
    void failuresBelowThresholdDoNotLock() {
        assumeTrue(redisReachable, "本机 Redis 不可达，跳过");
        LoginRateLimiter limiter = limiter(3, Duration.ofSeconds(5));
        String username = freshUsername("below-threshold");

        limiter.recordFailure(username);
        limiter.recordFailure(username);

        assertThatCode(() -> limiter.checkNotLocked(username)).doesNotThrowAnyException();
    }

    @Test
    void reachingThresholdLocksAccount() {
        assumeTrue(redisReachable, "本机 Redis 不可达，跳过");
        LoginRateLimiter limiter = limiter(3, Duration.ofSeconds(5));
        String username = freshUsername("threshold");

        limiter.recordFailure(username);
        limiter.recordFailure(username);
        limiter.recordFailure(username);

        assertThatThrownBy(() -> limiter.checkNotLocked(username))
                .isInstanceOf(AuthException.RateLimited.class);
    }

    @Test
    void failureCounterHasTtlSoItCannotGrowForever() {
        assumeTrue(redisReachable, "本机 Redis 不可达，跳过");
        LoginRateLimiter limiter = limiter(5, Duration.ofSeconds(30));
        String username = freshUsername("ttl");

        limiter.recordFailure(username);

        // 旧内存实现没有过期机制，任意用户名会无限占用内存；这里钉住计数键必须带 TTL
        assertThat(redis.getExpire("auth:login:fail:" + username)).isGreaterThan(0);
    }

    @Test
    void successfulLoginResetsFailures() {
        assumeTrue(redisReachable, "本机 Redis 不可达，跳过");
        LoginRateLimiter limiter = limiter(3, Duration.ofSeconds(5));
        String username = freshUsername("reset");

        limiter.recordFailure(username);
        limiter.recordFailure(username);
        limiter.reset(username);
        limiter.recordFailure(username);
        limiter.recordFailure(username);

        assertThatCode(() -> limiter.checkNotLocked(username)).doesNotThrowAnyException();
    }

    @Test
    void lockExpiresAndFailureCounterStartsOver() {
        assumeTrue(redisReachable, "本机 Redis 不可达，跳过");
        LoginRateLimiter limiter = limiter(2, Duration.ofMillis(300));
        String username = freshUsername("expiry");

        limiter.recordFailure(username);
        limiter.recordFailure(username);
        assertThatThrownBy(() -> limiter.checkNotLocked(username))
                .isInstanceOf(AuthException.RateLimited.class);

        sleep(600);

        // 锁到期 → 放行；且计数已随锁定重置：再失败 threshold-1 次不应立刻再次锁定
        assertThatCode(() -> limiter.checkNotLocked(username)).doesNotThrowAnyException();
        limiter.recordFailure(username);
        assertThatCode(() -> limiter.checkNotLocked(username)).doesNotThrowAnyException();
    }

    @Test
    void usernamesAreIsolated() {
        assumeTrue(redisReachable, "本机 Redis 不可达，跳过");
        LoginRateLimiter limiter = limiter(3, Duration.ofSeconds(5));
        String locked = freshUsername("isolated-a");
        String other = freshUsername("isolated-b");

        limiter.recordFailure(locked);
        limiter.recordFailure(locked);
        limiter.recordFailure(locked);

        assertThatThrownBy(() -> limiter.checkNotLocked(locked))
                .isInstanceOf(AuthException.RateLimited.class);
        assertThatCode(() -> limiter.checkNotLocked(other)).doesNotThrowAnyException();
    }

    /** 迁移的核心收益：计数在 Redis，任一实例的失败都能锁住其他实例（内存实现下不可能）。 */
    @Test
    void failuresRecordedByOneInstanceLockAnotherInstance() {
        assumeTrue(redisReachable, "本机 Redis 不可达，跳过");
        LoginRateLimiter instanceA = limiter(2, Duration.ofSeconds(5));
        LoginRateLimiter instanceB = limiter(2, Duration.ofSeconds(5));
        String username = freshUsername("two-instances");

        instanceA.recordFailure(username);
        instanceA.recordFailure(username);

        assertThatThrownBy(() -> instanceB.checkNotLocked(username))
                .isInstanceOf(AuthException.RateLimited.class);

        // 反向亦然：B 上的成功登录能清掉 A 记下的计数
        instanceB.reset(username);
        assertThatCode(() -> instanceA.checkNotLocked(username)).doesNotThrowAnyException();
    }

    @Test
    void redisUnavailableFailsOpen() {
        // 指向不可达端口：限流层不可用时必须放行登录，而不是抛异常或拖慢请求
        LettuceConnectionFactory unreachableFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(HOST, 1),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(200))
                        .shutdownTimeout(Duration.ZERO)
                        .build());
        unreachableFactory.afterPropertiesSet();
        StringRedisTemplate unreachable = new StringRedisTemplate(unreachableFactory);
        unreachable.afterPropertiesSet();
        try {
            LoginProperties properties = new LoginProperties();
            properties.setMaxFailures(3);
            LoginRateLimiter limiter = new LoginRateLimiter(unreachable, properties);

            assertThatCode(() -> limiter.checkNotLocked("fail-open-user")).doesNotThrowAnyException();
            assertThatCode(() -> limiter.recordFailure("fail-open-user")).doesNotThrowAnyException();
            assertThatCode(() -> limiter.reset("fail-open-user")).doesNotThrowAnyException();
        } finally {
            unreachableFactory.destroy();
        }
    }

    private static LoginRateLimiter limiter(int maxFailures, Duration lockDuration) {
        LoginProperties properties = new LoginProperties();
        properties.setMaxFailures(maxFailures);
        properties.setLockMinutes(lockDuration);
        return new LoginRateLimiter(redis, properties);
    }

    private String freshUsername(String tag) {
        String username = "test-" + tag + "-" + System.nanoTime();
        usedUsernames.add(username);
        return username;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试等待被中断", exception);
        }
    }
}
