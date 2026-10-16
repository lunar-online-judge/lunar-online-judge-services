package io.lunar.common.judge;

/**
 * 引擎评测结果（docs/architecture.md §9）：
 * verdict 是引擎原始值，由调用方归一化为 {@link JudgeVerdict}；passedCases/totalCases 供按测试点比例给分。
 *
 * @param engineSubmissionId 引擎侧任务 ID
 * @param verdict            引擎原始 verdict
 * @param timeMs             耗时（毫秒，可空）
 * @param memoryKb           峰值内存（KB，可空）
 * @param message            可读说明（编译错误输出、失败测试点等）
 * @param passedCases        通过的测试点数（引擎未上报时为空）
 * @param totalCases         测试点总数（引擎未上报时为空）
 */
public record JudgeResult(String engineSubmissionId, String verdict, Long timeMs, Long memoryKb, String message,
                          Integer passedCases, Integer totalCases) {

    /** 不区分测试点的引擎（如 mock）用这个构造器：通过数/总数留空，评分退回"满分或 0 分"。 */
    public JudgeResult(String engineSubmissionId, String verdict, Long timeMs, Long memoryKb, String message) {
        this(engineSubmissionId, verdict, timeMs, memoryKb, message, null, null);
    }
}
