package io.lunar.analytics.domain;

import io.lunar.common.api.ApiException;

/**
 * 分析读模型域异常：携带 docs/api.md 错误码注册表里的稳定 code 与对应 HTTP 状态。
 *
 * 与仓库既有约定一致（AuthException / ContestException / AiException 同样把子类声明在基类内）：
 * 这些子类只承载"码 + 状态 + 默认文案"，放在一起才看得清整套错误语义。
 */
public class AnalyticsException extends ApiException {

    /** 重算进行中：让"请求重算"与"重算占用的 409"两条路径共用同一个字面量，避免两处各写一份而漂移。 */
    public static final String REBUILD_IN_PROGRESS_CODE = "ANALYTICS_REBUILD_IN_PROGRESS";

    private AnalyticsException(String code, int httpStatus, String message) {
        super(code, httpStatus, message);
    }

    /**
     * 404：目标资源不存在，**或不在调用者的资源范围内**。
     *
     * 两种情形共用同一个码是刻意的：区分它们等于告诉调用方"这场考试存在但不归你"。
     * 路径参数里的 id 不是合法 UUID 时也走这里——"这个 id 什么都指不到"与"这个资源不存在"对外没有区别。
     */
    public static final class ResourceNotFound extends AnalyticsException {
        public ResourceNotFound() {
            super("ANALYTICS_RESOURCE_NOT_FOUND", 404, "资源不存在");
        }
    }

    /** 409：同一场考试的重算正在进行中（单飞；重复触发会让两次全量替换互相覆盖）。 */
    public static final class RebuildInProgress extends AnalyticsException {
        public RebuildInProgress(String contestId) {
            super(REBUILD_IN_PROGRESS_CODE, 409, "该考试的重算正在进行中：" + contestId);
        }
    }

    /**
     * 400：区间参数不合法（档宽越界、时间窗起止颠倒）。
     *
     * 与 VALIDATION_FAILED 的分工：那个码由全局处理器用于请求体/字段校验，
     * 这里表达的是"参数本身合法、但取了一个在本业务里没有意义的区间"。
     */
    public static final class InvalidRange extends AnalyticsException {
        public InvalidRange(String message) {
            super("ANALYTICS_INVALID_RANGE", 400, message);
        }
    }
}
