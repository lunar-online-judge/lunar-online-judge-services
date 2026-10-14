package io.lunar.auth.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 登录失败限流：同一用户名连续失败达到阈值后短暂锁定，成功登录清零。
 *
 * 计数与锁定标记存 Redis（docs/middleware.md"Redis"节）：多实例共享、重启不清零、TTL 自动清理，
 * 不再是原先的单实例内存实现。
 *
 * fail-open：Redis 不可用时放行登录并告警——限流属短期状态层，其不可用不应让全站无法登录；
 * 代价是该窗口内防爆破能力暂时失效，故降级时打 warn 日志。
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private static final String FAIL_KEY_PREFIX = "auth:login:fail:";
    private static final String LOCK_KEY_PREFIX = "auth:login:lock:";

    /**
     * 记一次失败并（必要时）写锁定标记，整体原子执行。
     * 必须原子：若 INCR 与 PEXPIRE 之间中断，会留下永不过期的计数键，
     * 于是锁到期后下一次失败立刻再次锁定（旧内存实现即有此问题）。
     * KEYS[1]=计数键 KEYS[2]=锁定键；ARGV[1]=失败阈值 ARGV[2]=窗口毫秒（即锁定时长）。
     */
    private static final RedisScript<Long> RECORD_FAILURE_SCRIPT = new DefaultRedisScript<>("""
            local failures = redis.call('INCR', KEYS[1])
            if failures == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            if failures >= tonumber(ARGV[1]) then
                redis.call('SET', KEYS[2], '1', 'PX', ARGV[2])
                redis.call('DEL', KEYS[1])
            end
            return failures
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final long windowMillis;

    /**
     * 容器装配入口：显式标注 @Autowired——本类另有一个包级可见的 Duration 构造器供单测传入短 TTL，
     * 两个构造器并存时 Spring 需要明确的注入点，否则会去找默认构造器并启动失败
     * （与 GoJudgeAdapter 同一坑，见其构造器注释）。
     */
    @Autowired
    public LoginRateLimiter(StringRedisTemplate redis,
                            @Value("${auth.login.max-failures:5}") int maxFailures,
                            @Value("${auth.login.lock-minutes:5}") long lockMinutes) {
        this(redis, maxFailures, Duration.ofMinutes(lockMinutes));
    }

    LoginRateLimiter(StringRedisTemplate redis, int maxFailures, Duration lockDuration) {
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.windowMillis = lockDuration.toMillis();
    }

    /** 锁定中则抛出 {@link AuthException.RateLimited}；Redis 不可用时放行。 */
    public void checkNotLocked(String username) {
        boolean locked;
        try {
            locked = Boolean.TRUE.equals(redis.hasKey(lockKey(username)));
        } catch (DataAccessException exception) {
            log.warn("登录限流检查失败，本次放行（fail-open）：username={} err={}",
                    username, exception.getMessage());
            return;
        }
        if (locked) {
            throw new AuthException.RateLimited();
        }
    }

    public void recordFailure(String username) {
        try {
            redis.execute(RECORD_FAILURE_SCRIPT, List.of(failKey(username), lockKey(username)),
                    String.valueOf(maxFailures), String.valueOf(windowMillis));
        } catch (DataAccessException exception) {
            log.warn("登录失败计数写入失败（fail-open：本窗口防爆破能力暂时失效）：username={} err={}",
                    username, exception.getMessage());
        }
    }

    public void reset(String username) {
        try {
            redis.delete(List.of(failKey(username), lockKey(username)));
        } catch (DataAccessException exception) {
            log.warn("登录失败计数重置失败：username={} err={}", username, exception.getMessage());
        }
    }

    private static String failKey(String username) {
        return FAIL_KEY_PREFIX + username;
    }

    private static String lockKey(String username) {
        return LOCK_KEY_PREFIX + username;
    }
}
