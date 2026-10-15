package io.lunar.submission.domain;

import io.lunar.common.api.ApiException;

/** 提交域异常：携带 api.md 错误码注册表的稳定 code 与 HTTP 状态。 */
public class SubmissionException extends ApiException {

    private SubmissionException(String code, int httpStatus, String message) {
        super(code, httpStatus, message);
    }

    /** 404：提交不存在。 */
    public static final class NotFound extends SubmissionException {
        public NotFound() {
            super("SUBMISSION_NOT_FOUND", 404, "提交不存在");
        }
    }

    /** 409：幂等键冲突（同键不同请求内容）。 */
    public static final class Duplicate extends SubmissionException {
        public Duplicate() {
            super("SUBMISSION_DUPLICATE", 409, "幂等键重复且请求内容不一致");
        }
    }

    /** 400：语言不在题目白名单。 */
    public static final class LanguageNotAllowed extends SubmissionException {
        public LanguageNotAllowed() {
            super("SUBMISSION_LANGUAGE_NOT_ALLOWED", 400, "语言不在题目允许列表内");
        }
    }

    /** 403：不在竞赛名单。 */
    public static final class NotSubmittable extends SubmissionException {
        public NotSubmittable(String reason) {
            super("CONTEST_NOT_ENROLLED", 403, "当前不可提交：" + reason);
        }
    }

    /** 409：考生会话不存在或未激活（docs/api.md 注册表：SESSION_NOT_ACTIVE）。 */
    public static final class SessionNotActive extends SubmissionException {
        public SessionNotActive(String reason) {
            super("SESSION_NOT_ACTIVE", 409, "考试会话未激活，请重新进入考试：" + reason);
        }
    }

    /** 409：竞赛未在进行（时间窗口外/状态不符）。 */
    public static final class ContestNotRunning extends SubmissionException {
        public ContestNotRunning(String reason) {
            super("CONTEST_NOT_RUNNING", 409, "竞赛未开始或已结束：" + reason);
        }
    }

    /** 409：非法状态流转（判决回写时提交已是终态）。 */
    public static final class InvalidStatusTransition extends SubmissionException {
        public InvalidStatusTransition(String from) {
            super("SUBMISSION_INVALID_TRANSITION", 409, "提交状态不允许该流转：" + from);
        }
    }
}
