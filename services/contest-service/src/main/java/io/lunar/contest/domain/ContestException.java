package io.lunar.contest.domain;

/** 竞赛域异常：携带 api.md 错误码注册表的稳定 code 与 HTTP 状态，由 api/ 统一转换。 */
public class ContestException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    private ContestException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }

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
}
