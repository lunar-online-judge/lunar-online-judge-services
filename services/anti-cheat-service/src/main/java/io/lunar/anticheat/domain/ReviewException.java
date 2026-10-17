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

    /**
     * 400：上报的事件类型格式不合法。
     *
     * 为什么只校验格式、不校验白名单：文档约定"未知类型保守按 MEDIUM 处理"，新增类型不该因为
     * 服务端枚举没更新而被丢弃；但必须是大写下划线形式，否则任意文本（含换行）会被写进
     * 结构化字段、日志与 analytics 的读模型。
     */
    public static final class InvalidEventType extends ReviewException {
        public InvalidEventType() {
            super("MONITOR_EVENT_TYPE_INVALID", 400, "eventType 必须是大写下划线形式，如 DEBUGGER_DETECTED");
        }
    }

    /** 429：上报过于频繁（按学生做窗口计数，防一条循环打满监考视图与风险读模型）。 */
    public static final class ReportRateLimited extends ReviewException {
        public ReportRateLimited(int maxPerWindow, long windowMinutes) {
            super("MONITOR_REPORT_RATE_LIMITED", 429,
                    "上报过于频繁：每 " + windowMinutes + " 分钟最多 " + maxPerWindow + " 条");
        }
    }
}
