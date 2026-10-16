package io.lunar.problem.domain;

import io.lunar.common.api.ApiException;

/** 题目域异常：携带 api.md 错误码注册表的稳定 code 与 HTTP 状态。 */
public class ProblemException extends ApiException {

    private ProblemException(String code, int httpStatus, String message) {
        super(code, httpStatus, message);
    }

    /** 404：题目不存在。 */
    public static final class NotFound extends ProblemException {
        public NotFound() {
            super("PROBLEM_NOT_FOUND", 404, "题目不存在");
        }
    }

    /** 404：题目版本不存在。 */
    public static final class VersionNotFound extends ProblemException {
        public VersionNotFound() {
            super("PROBLEM_VERSION_NOT_FOUND", 404, "题目版本不存在");
        }
    }

    /** 409：版本已发布不可变更（发布后不原地修改，改动须创建新版本）。 */
    public static final class VersionImmutable extends ProblemException {
        public VersionImmutable() {
            super("PROBLEM_VERSION_IMMUTABLE", 409, "版本已发布，不可修改；请创建新版本");
        }
    }

    /**
     * 413：测试点数据超过服务间传输上限。
     * 测试点随 submission.created 事件进 MQ，超限会让消息永远发不出去（Outbox 每轮重试），
     * 所以在上游就拦住并给出明确错误，而不是让它卡在事件里。
     */
    public static final class TestdataTooLarge extends ProblemException {
        public TestdataTooLarge(long actualBytes, long maxBytes) {
            super("PROBLEM_TESTDATA_TOO_LARGE", 413,
                    "测试点数据过大：" + actualBytes + " 字节，服务间传输上限 " + maxBytes + " 字节");
        }
    }
}
