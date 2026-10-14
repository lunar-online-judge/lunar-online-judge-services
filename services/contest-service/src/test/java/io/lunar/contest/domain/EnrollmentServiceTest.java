package io.lunar.contest.domain;

import io.lunar.contest.infra.AuthInternalClient;
import io.lunar.contest.infra.CandidateEntity;
import io.lunar.contest.infra.CandidateMapper;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnrollmentServiceTest {

    private final CandidateMapper candidateMapper = mock(CandidateMapper.class);
    private final ContestMapper contestMapper = mock(ContestMapper.class);
    private final AuthInternalClient authClient = mock(AuthInternalClient.class);
    private final EnrollmentService service = new EnrollmentService(candidateMapper,
            new ContestService(contestMapper), authClient);

    @BeforeAll
    static void initMpMetadata() {
        // LambdaQueryWrapper 依赖 MP 的实体元数据缓存；纯单测需手动初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CandidateEntity.class);
    }

    @BeforeEach
    void contestExists() {
        ContestEntity contest = new ContestEntity();
        contest.setId("c1");
        contest.setStatus("PUBLISHED");
        when(contestMapper.selectById("c1")).thenReturn(contest);
    }

    private static CandidateEntity candidate(String studentId, String studentNo, String status) {
        CandidateEntity candidate = new CandidateEntity();
        candidate.setId("rel_1");
        candidate.setContestId("c1");
        candidate.setStudentId(studentId);
        candidate.setStudentNoSnapshot(studentNo);
        candidate.setUsernameSnapshot("student_" + studentNo);
        candidate.setDisplayNameSnapshot("学生" + studentNo);
        candidate.setStatus(status);
        candidate.setEnrolledAt(Instant.now());
        return candidate;
    }

    @Test
    void addCreatesAccountAndEnrolls() {
        when(candidateMapper.selectOne(any())).thenReturn(null);
        when(authClient.findStudent("20260001", "student_20260001", "tok")).thenReturn(null);
        when(authClient.createStudent("20260001", "student_20260001", "张三", "init-pass-123", "tok"))
                .thenReturn(Map.of("studentId", "stu_1", "username", "student_20260001",
                        "passwordChangeRequired", true));

        EnrollmentService.EnrollmentResult view =
                service.add("c1", "20260001", "student_20260001", "张三", "init-pass-123", "tok", "teacher_1");

        assertThat(view.studentId()).isEqualTo("stu_1");
        assertThat(view.enrollmentStatus()).isEqualTo("ENROLLED");
        // 新建账号下发了临时密码 → 必须改密（docs/api.md"添加学生"契约字段）
        assertThat(view.passwordChangeRequired()).isTrue();
        ArgumentCaptor<CandidateEntity> captor = ArgumentCaptor.forClass(CandidateEntity.class);
        verify(candidateMapper).insert(captor.capture());
        assertThat(captor.getValue().getStudentNoSnapshot()).isEqualTo("20260001");
        assertThat(captor.getValue().getAddedByTeacherId()).isEqualTo("teacher_1");
    }

    @Test
    void addReusesExistingAuthAccount() {
        when(candidateMapper.selectOne(any())).thenReturn(null);
        when(authClient.findStudent("20260001", "student_20260001", "tok"))
                .thenReturn(Map.of("studentId", "stu_exists", "username", "student_20260001",
                        "displayName", "老账号"));

        EnrollmentService.EnrollmentResult view =
                service.add("c1", "20260001", "student_20260001", "张三", "init-pass-123", "tok", "teacher_1");

        assertThat(view.studentId()).isEqualTo("stu_exists");
        // 账号复用：本次没有下发任何密码，不应要求改密
        assertThat(view.passwordChangeRequired()).isFalse();
        verify(authClient, never()).createStudent(any(), any(), any(), any(), any());
    }

    @Test
    void duplicateActiveEnrollmentRejected() {
        when(candidateMapper.selectOne(any())).thenReturn(candidate("stu_1", "20260001", "ENROLLED"));

        assertThatThrownBy(() -> service.add("c1", "20260001", "student_20260001", "张三",
                "init-pass-123", "tok", "teacher_1"))
                .isInstanceOf(ContestException.AlreadyEnrolled.class);
    }

    @Test
    void removedStudentIsReactivated() {
        // 第一次 selectOne（按学号查）返回 REMOVED 行 → 不拒绝；第二次（按 studentId 查）返回同一行
        CandidateEntity removed = candidate("stu_1", "20260001", "REMOVED");
        when(candidateMapper.selectOne(any())).thenReturn(removed, removed);
        when(authClient.findStudent("20260001", "student_20260001", "tok"))
                .thenReturn(Map.of("studentId", "stu_1", "username", "student_20260001"));

        EnrollmentService.EnrollmentResult view =
                service.add("c1", "20260001", "student_20260001", "张三", "init-pass-123", "tok", "teacher_1");

        assertThat(view.enrollmentStatus()).isEqualTo("ENROLLED");
        verify(candidateMapper).updateById(removed);
    }

    @Test
    void resetPasswordRequiresEnrolledStudent() {
        when(candidateMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.resetPassword("c1", "stu_x", "temp-pass-456", "tok", "teacher_1"))
                .isInstanceOf(ContestException.NotEnrolled.class);
    }

    @Test
    void removeSoftDeletesEnrollment() {
        when(candidateMapper.selectOne(any())).thenReturn(candidate("stu_1", "20260001", "ENROLLED"));

        service.remove("c1", "stu_1", "teacher_1");

        ArgumentCaptor<CandidateEntity> captor = ArgumentCaptor.forClass(CandidateEntity.class);
        verify(candidateMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("REMOVED");
        assertThat(captor.getValue().getRemovedAt()).isNotNull();
    }

    @Test
    void removeAlreadyRemovedThrowsNotEnrolled() {
        when(candidateMapper.selectOne(any())).thenReturn(candidate("stu_1", "20260001", "REMOVED"));

        assertThatThrownBy(() -> service.remove("c1", "stu_1", "teacher_1"))
                .isInstanceOf(ContestException.NotEnrolled.class);
    }
}
