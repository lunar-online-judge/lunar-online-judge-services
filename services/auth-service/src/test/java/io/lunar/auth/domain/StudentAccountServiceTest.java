package io.lunar.auth.domain;

import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import io.lunar.auth.infra.UserEntity;
import io.lunar.auth.infra.UserMapper;
import io.lunar.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
    private final StudentAccountService service =
            new StudentAccountService(userMapper, passwordEncoder);

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

    static {
        // LambdaQueryWrapper 依赖 MP 的实体元数据缓存；纯单测需手动初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                UserEntity.class);
    }

    /**
     * 老师查学生时必须带上归属条件——否则任意老师能按学号逐个试探，枚举全平台学生。
     * 单测无法连库，因此校验生成的 SQL 片段里确实带了 managed_by_teacher_id。
     */
    @Test
    void teacherLookupIsRestrictedToOwnStudents() {
        when(userMapper.selectOne(any())).thenReturn(null);

        service.findStudent("20260001", null, "teacher_a", Role.TEACHER.name());

        ArgumentCaptor<LambdaQueryWrapper<UserEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectOne(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("managed_by_teacher_id"),
                "老师查询必须按归属过滤");
    }

    @Test
    void adminLookupIsNotRestricted() {
        when(userMapper.selectOne(any())).thenReturn(null);

        service.findStudent("20260001", null, "admin_1", Role.ADMIN.name());

        ArgumentCaptor<LambdaQueryWrapper<UserEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectOne(captor.capture());
        assertFalse(captor.getValue().getSqlSegment().contains("managed_by_teacher_id"),
                "管理员不受归属限制");
    }
}