package io.lunar.ai.domain;

import io.lunar.ai.infra.AiJobEntity;

/**
 * 作业处理器：一类作业一个实现，由 {@code AiJobWorker} 按 {@link #jobType()} 分发。
 *
 * 处理器只做两件事：**取输入、调模型、产出结论**。落库、发事件、写审计、状态流转
 * 一律由 worker 与 {@code AiJobService} 统一负责——否则每个处理器都要重复一遍事务与幂等逻辑，
 * 迟早出现"某个类型忘了写审计"。
 */
public interface AiJobHandler {

    AiJobType jobType();

    /**
     * 执行作业并产出结论内容。
     *
     * 允许抛出 {@link AiException} 子类：worker 会把它记成作业的 {@code error_code}，
     * 并按尝试次数决定重试还是转 FAILED。
     */
    AiFindingDraft execute(AiJobEntity job);
}
