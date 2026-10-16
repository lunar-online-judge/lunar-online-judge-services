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

    /**
     * 400：审核原因不合法（BE-03）。
     * 终态结论（CONFIRM/DISMISS）必须给出可读原因——单一事件不能自动判作弊，处置要可追溯；
     * 原因同时有长度上限，避免超长文本撑爆存储与日志。
     */
    public static final class InvalidReason extends ReviewException {
        public InvalidReason(String message) {
            super("MONITOR_REVIEW_REASON_INVALID", 400, message);
        }
    }
}
