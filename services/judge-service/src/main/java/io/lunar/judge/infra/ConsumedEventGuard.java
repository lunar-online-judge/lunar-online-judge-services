package io.lunar.judge.infra;

import io.lunar.judge.config.JudgeConsumerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 事件消费去重（judge 没有数据库，只能用 Redis 这个短期状态层做幂等，见 docs/middleware.md"Redis"节）。
 *
 * 为什么是"先查、处理成功后再登记"，而不是常见的一上来 SETNX 占坑：
 * judge 的评测是不可撤销的重活，但它的下游写入是幂等的（submission 侧有 CAS + consumed_events 闸门）。
 * 若一上来就占坑，评测中途失败后 RocketMQ 重投会看到"坑位已占"而直接 ack —— 判决永久丢失。
 * 先查后写的代价只是极端并发下多评一次，属于"宁可重复计算，不可丢判决"的取舍。
 *
 * fail-open：Redis 不可用时不去重（{@link #isProcessed} 返回 false），评测链路的可用性优先于去重；
 * 这与 auth 的登录限流 fail-open 是同一套理由——短期状态层不可用不该让主链路停摆。
 */
@Component
public class ConsumedEventGuard {

    private static final Logger log = LoggerFactory.getLogger(ConsumedEventGuard.class);

    private static final String KEY_PREFIX = "judge:consumed:";

    private final StringRedisTemplate redis;
    private final JudgeConsumerProperties properties;

    public ConsumedEventGuard(StringRedisTemplate redis, JudgeConsumerProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 该事件此前是否已成功处理过。eventId 为空（旧格式裸 payload 消息）时始终返回 false，不做去重。 */
    public boolean isProcessed(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(keyOf(eventId)));
        } catch (RuntimeException exception) {
            log.warn("去重查询失败，按未处理继续处理：eventId={}", eventId, exception);
            return false;
        }
    }

    /** 处理成功后登记。TTL 过期后同一 eventId 即使重投也会被重新评测——重复计算可接受，丢判决不可接受。 */
    public void markProcessed(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(keyOf(eventId), "1", Duration.ofHours(properties.getConsumedEventTtlHours()));
        } catch (RuntimeException exception) {
            log.warn("去重登记失败：eventId={}", eventId, exception);
        }
    }

    private static String keyOf(String eventId) {
        return KEY_PREFIX + eventId;
    }
}
