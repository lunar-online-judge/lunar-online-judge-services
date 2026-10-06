package io.lunar.auth.domain;

/** 用户/学生账号域异常：携带 api.md 错误码注册表的稳定 code 与 HTTP 状态。 */
public class UserException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    private UserException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }

    /** 404：目标用户不存在。 */
    public static final class NotFound extends UserException {
        public NotFound() {
            super("AUTH_USER_NOT_FOUND", 404, "用户不存在");
        }
    }

    /** 409：登录账户名已被占用（全局唯一约束）。 */
    public static final class UsernameExists extends UserException {
        public UsernameExists() {
            super("AUTH_USERNAME_EXISTS", 409, "登录账户名已存在");
        }
    }

    /** 409：学号已被占用（组织内唯一约束）。 */
    public static final class StudentNoExists extends UserException {
        public StudentNoExists() {
            super("AUTH_STUDENT_NO_EXISTS", 409, "学号已存在");
        }
    }

    /** 400：密码强度不满足要求。 */
    public static final class WeakPassword extends UserException {
        public WeakPassword() {
            super("AUTH_WEAK_PASSWORD", 400, "密码强度不满足要求（至少 8 位）");
        }
    }
}
