package io.lunar.ai.domain;

/** 作业状态。FAILED 是终态（attempts 用尽）；PENDING → RUNNING → SUCCEEDED 是正常路径。 */
public enum AiJobStatus {

    PENDING,

    RUNNING,

    SUCCEEDED,

    FAILED
}
