package io.lunar.contest.domain;

import io.lunar.common.api.ApiException;

/** 竞赛域异常：携带 api.md 错误码注册表的稳定 code 与 HTTP 状态，由全局异常处理器统一转换。 */
public class ContestException extends ApiException {

    private ContestException(String code, int httpStatus, String message) {
        super(code, httpStatus, message);
    }

    /** 404：竞赛不存在。 */
    public static final class NotFound extends ContestException {
        public NotFound() {
            super("CONTEST_NOT_FOUND", 404, "竞赛不存在");
        }
    }

    /** 409：非法状态流转（如草稿直接发布、已归档再变更）。 */
    public static final class InvalidTransition extends ContestException {
        public InvalidTransition(ContestStatus from, ContestStatus to) {
            super("CONTEST_INVALID_TRANSITION", 409, "不允许从 " + from + " 流转到 " + to);
        }
    }

    /** 409：发布前校验未通过（时间窗口、名单、题目等未配置齐，exam-process §1）。 */
    public static final class PublishIncomplete extends ContestException {
        public PublishIncomplete(String missing) {
            super("CONTEST_PUBLISH_INCOMPLETE", 409, "发布前需完成配置：" + missing);
        }
    }

    /** 409：该学生已在名单中有效报名（同一 contestId+studentNo 只能有一条有效报名关系）。 */
    public static final class AlreadyEnrolled extends ContestException {
        public AlreadyEnrolled() {
            super("CONTEST_STUDENT_ALREADY_ENROLLED", 409, "该学生已在本竞赛名单中");
        }
    }

    /** 403：报名关系不存在或已移出（docs/api.md 错误码注册表）。 */
    public static final class NotEnrolled extends ContestException {
        public NotEnrolled() {
            super("CONTEST_NOT_ENROLLED", 403, "该学生不在本竞赛名单中");
        }
    }

    /** 409：竞赛未开始或尚未发布（服务端时间判定，docs/exam-process.md §4）。 */
    public static final class NotRunning extends ContestException {
        public NotRunning() {
            super("CONTEST_NOT_RUNNING", 409, "竞赛未开始或已结束");
        }
    }

    /** 409：考试入口已关闭（统一结束时间之后）。 */
    public static final class Closed extends ContestException {
        public Closed() {
            super("CONTEST_CLOSED", 409, "考试入口已关闭");
        }
    }

    /** 409：考生会话不存在或未处于 ACTIVE（心跳/恢复场景）。 */
    public static final class SessionNotActive extends ContestException {
        public SessionNotActive() {
            super("SESSION_NOT_ACTIVE", 409, "考试会话未激活，请重新进入考试");
        }
    }
}
