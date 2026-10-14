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
}
