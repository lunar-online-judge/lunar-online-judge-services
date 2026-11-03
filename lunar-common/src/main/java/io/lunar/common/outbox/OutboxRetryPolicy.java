package io.lunar.common.outbox;

import java.time.Instant;

/**
 * Outbox 重试策略（③ 去重：submission / anti-cheat / judge 的 Publisher 此前各写了一份完全相同的实现）。
 *
 * 为什么抽出来：退避与死信的判定是"投不出去的消息会不会丢"的唯一依据，三份复制意味着
 * 改一处忘两处——而这类偏差只有到真出事（消息静默丢失）时才暴露。
 *
 * 只保留**纯函数**：不碰数据库、不碰 MQ。各服务仍用自己的表、Entity 与 Mapper
 * （表结构不动，这是重构而不是迁移）。
 */
public final class OutboxRetryPolicy {

    /** 退避上限：2^8 = 256s 再夹到 300s，避免 attempts 增长后出现离谱的等待。 */
    private static final long MAX_BACKOFF_SECONDS = 300L;

    /** 位移上限：1L << 8 = 256，防止 attempts 很大时移位溢出。 */
    private static final int MAX_SHIFT = 8;

    private OutboxRetryPolicy() {
    }

    /** 该次失败是否应转入死信（尝试次数已达上限）。 */
    public static boolean isDeadLettered(int attempts, int maxAttempts) {
        return attempts >= maxAttempts;
    }

    /** 下次可重试时间：指数退避（2s → 4s → … → 300s 封顶）。 */
    public static Instant nextAttemptAt(int attempts, Instant now) {
        long backoffSeconds = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempts, MAX_SHIFT));
        return now.plusSeconds(backoffSeconds);
    }
}
