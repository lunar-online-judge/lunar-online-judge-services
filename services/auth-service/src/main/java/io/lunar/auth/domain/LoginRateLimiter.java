package io.lunar.auth.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流：同一用户名连续失败达到阈值后短暂锁定，成功登录清零。
 * 当前为内存实现（实例重启即清零，多实例不共享），Redis 接入后迁移，见 development-guide 第 5 节第 8 步。
 */
@Component
public class LoginRateLimiter {

    private record State(int failures, Instant lockedUntil) {}

    private final int maxFailures;
    private final Duration lockDuration;
    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public LoginRateLimiter(@Value("${auth.login.max-failures:5}") int maxFailures,
                            @Value("${auth.login.lock-minutes:5}") long lockMinutes) {
        this(maxFailures, Duration.ofMinutes(lockMinutes), Clock.systemUTC());
    }

    LoginRateLimiter(int maxFailures, Duration lockDuration, Clock clock) {
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    /** 锁定中则抛出 {@link AuthException.RateLimited}。 */
    public void checkNotLocked(String username) {
        State state = states.get(username);
        if (state != null && state.lockedUntil() != null && clock.instant().isBefore(state.lockedUntil())) {
            throw new AuthException.RateLimited();
        }
    }

    public void recordFailure(String username) {
        states.compute(username, (k, state) -> {
            int failures = (state == null ? 0 : state.failures()) + 1;
            Instant lockedUntil = failures >= maxFailures ? clock.instant().plus(lockDuration) : null;
            return new State(failures, lockedUntil);
        });
    }

    public void reset(String username) {
        states.remove(username);
    }
}
