package io.lunar.common.judge;

import java.util.Arrays;
import java.util.Optional;

/**
 * 判题终态：枚举名即 `lunar-submission-judged` 事件与 `submission_schema.submissions.status`
 * 的契约值（docs/architecture.md §8 的归一化目标，也是客户端可见状态），改名等于改协议。
 *
 * 全平台唯一权威：judge 侧产出与 submission 侧校验都引用本枚举，不得再各自维护字符串清单——
 * 此前两处手写清单漂移，真机下的 TLE/MLE/OLE/RE 会被下游归一成 SYSTEM_ERROR。
 */
public enum JudgeVerdict {
    ACCEPTED,
    WRONG_ANSWER,
    COMPILE_ERROR,
    TIME_LIMIT_EXCEEDED,
    MEMORY_LIMIT_EXCEEDED,
    RUNTIME_ERROR,
    OUTPUT_LIMIT_EXCEEDED,
    SYSTEM_ERROR;

    /** 解析契约字符串；未知取值（含引擎尚未出结果的 PENDING）返回 empty，不用 valueOf 以免用异常控制流程。 */
    public static Optional<JudgeVerdict> parse(String verdictCode) {
        return Arrays.stream(values())
                .filter(verdict -> verdict.name().equals(verdictCode))
                .findFirst();
    }
}
