package io.lunar.anticheat.domain;

import io.lunar.common.api.ApiException;

/** 审核域异常：携带 api.md 错误码注册表的稳定 code 与 HTTP 状态。 */
public class ReviewException extends ApiException {

    private ReviewException(String code, int httpStatus, String message) {
        super(code, httpStatus, message);
    }

    /** 404：风险事件不存在。 */
    public static final class NotFound extends ReviewException {
        public NotFound() {
            super("MONITOR_EVENT_NOT_FOUND", 404, "风险事件不存在");
        }
    }

    /** 409：审核流转非法（重复终审、跳步等）。 */
    public static final class InvalidTransition extends ReviewException {
        public InvalidTransition(String from, String action) {
            super("MONITOR_INVALID_TRANSITION", 409, "事件状态 " + from + " 不允许审核动作 " + action);
        }
    }
}
