package io.lunar.auth.domain;

import io.lunar.auth.infra.UserEntity;
import io.lunar.auth.infra.UserMapper;
import io.lunar.common.audit.AuditRecorder;
import io.lunar.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 教师-学生归属校验（BE-01 剩余项）：老师只能重置自己名下学生的密码。
 *
 * 这条规则是"一个教师账号即可接管其他班级学生"的最后一道闸，必须有测试锁住。
 * 越权与"不存在"同码（{@link UserException.NotFound}），不泄露账号存在性。
 */
class StudentAccountServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AuditRecorder auditRecorder = mock(AuditRecorder.class);
    private final StudentAccountService service =
            new StudentAccountService(userMapper, passwordEncoder, auditRecorder);

    private static UserEntity studentManagedBy(String teacherId) {
        UserEntity user = new UserEntity();
        user.setId("stu_1");
        user.setRole(Role.CANDIDATE.name());
        user.setManagedByTeacherId(teacherId);
        return user;
    }

    @Test
    void teacherCannotResetStudentOfAnotherTeacher() {
        when(userMapper.selectById("stu_1")).thenReturn(studentManagedBy("teacher_a"));

        assertThrows(UserException.NotFound.class, () -> service.resetStudentPassword(
                "stu_1", "temp-pass-456", "Bearer token", "teacher_b", Role.TEACHER.name()));
    }

    @Test
    void teacherCanResetOwnStudent() {
        when(userMapper.selectById("stu_1")).thenReturn(studentManagedBy("teacher_a"));
        when(passwordEncoder.encode("temp-pass-456")).thenReturn("hashed");

        assertDoesNotThrow(() -> service.resetStudentPassword(
                "stu_1", "temp-pass-456", "Bearer token", "teacher_a", Role.TEACHER.name()));
    }

    @Test
    void adminCanResetAnyStudent() {
        when(userMapper.selectById("stu_1")).thenReturn(studentManagedBy("teacher_a"));
        when(passwordEncoder.encode("temp-pass-456")).thenReturn("hashed");

        assertDoesNotThrow(() -> service.resetStudentPassword(
                "stu_1", "temp-pass-456", "Bearer token", "admin_1", Role.ADMIN.name()));
    }
}
