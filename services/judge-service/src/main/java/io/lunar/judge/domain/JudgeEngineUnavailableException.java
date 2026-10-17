package io.lunar.judge.domain;

/**
 * 评测引擎**瞬时不可用**（连不上、超时、5xx）。
 *
 * 为什么单独成类型：引擎抖动是**可重试**的，而 SYSTEM_ERROR 是判题终态（submission 侧用前态条件更新
 * 固化，重复判决会被拒绝）。把瞬时故障归一化成 SYSTEM_ERROR，等于给考生判 0 分且事后无法改判——
 * 这是第二批正确性项里最伤人的一条。抛这个异常后，消费端会把它转成 RuntimeException 交给 RocketMQ 重投；
 * 消费端有 eventId 幂等闸门，重复评测不会重复记账。
 */
public class JudgeEngineUnavailableException extends RuntimeException {

    public JudgeEngineUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
