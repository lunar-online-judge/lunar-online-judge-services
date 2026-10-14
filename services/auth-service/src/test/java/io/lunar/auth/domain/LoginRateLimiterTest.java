package io.lunar.auth.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

    private static final class SettableClock extends Clock {
        Instant instant = Instant.parse("2026-10-05T00:00:00Z");

        void advance(Duration duration) { instant = instant.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private final SettableClock clock = new SettableClock();
    private final LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(5), clock);

    @Test
    void failuresBelowThresholdDoNotLock() {
        limiter.recordFailure("user");
        limiter.recordFailure("user");

        assertThatCode(() -> limiter.checkNotLocked("user")).doesNotThrowAnyException();
    }

    @Test
    void reachingThresholdLocksAccount() {
        limiter.recordFailure("user");
        limiter.recordFailure("user");
        limiter.recordFailure("user");

        assertThatThrownBy(() -> limiter.checkNotLocked("user"))
                .isInstanceOf(AuthException.RateLimited.class);
    }

    @Test
    void successfulLoginResetsFailures() {
        limiter.recordFailure("user");
        limiter.recordFailure("user");
        limiter.reset("user");

        assertThatCode(() -> limiter.checkNotLocked("user")).doesNotThrowAnyException();
        // 重置后失败计数从头开始
        limiter.recordFailure("user");
        limiter.recordFailure("user");
        assertThatCode(() -> limiter.checkNotLocked("user")).doesNotThrowAnyException();
    }

    @Test
    void lockExpiresAfterLockDuration() {
        limiter.recordFailure("user");
        limiter.recordFailure("user");
        limiter.recordFailure("user");
        assertThatThrownBy(() -> limiter.checkNotLocked("user"))
                .isInstanceOf(AuthException.RateLimited.class);

        clock.advance(Duration.ofMinutes(6));

        assertThatCode(() -> limiter.checkNotLocked("user")).doesNotThrowAnyException();
    }

    @Test
    void usernamesAreIsolated() {
        limiter.recordFailure("a");
        limiter.recordFailure("a");
        limiter.recordFailure("a");

        assertThatThrownBy(() -> limiter.checkNotLocked("a")).isInstanceOf(AuthException.RateLimited.class);
        assertThatCode(() -> limiter.checkNotLocked("b")).doesNotThrowAnyException();
    }
}
