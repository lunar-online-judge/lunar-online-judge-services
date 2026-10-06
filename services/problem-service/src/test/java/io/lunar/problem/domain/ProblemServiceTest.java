package io.lunar.problem.domain;

import io.lunar.problem.infra.ProblemEntity;
import io.lunar.problem.infra.ProblemMapper;
import io.lunar.problem.infra.ProblemVersionEntity;
import io.lunar.problem.infra.ProblemVersionMapper;
import io.lunar.problem.infra.TestcaseEntity;
import io.lunar.problem.infra.TestcaseMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProblemServiceTest {

    private final ProblemMapper problemMapper = mock(ProblemMapper.class);
    private final ProblemVersionMapper versionMapper = mock(ProblemVersionMapper.class);
    private final TestcaseMapper testcaseMapper = mock(TestcaseMapper.class);
    private final ProblemService service = new ProblemService(problemMapper, versionMapper, testcaseMapper);

    @BeforeAll
    static void initMpMetadata() {
        for (Class<?> entity : List.of(ProblemVersionEntity.class, TestcaseEntity.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                    entity);
        }
    }

    private static ProblemVersionEntity version(String id, String problemId, int no, String status) {
        ProblemVersionEntity v = new ProblemVersionEntity();
        v.setId(id);
        v.setProblemId(problemId);
        v.setVersionNo(no);
        v.setTitle("A+B");
        v.setStatement("求和");
        v.setTimeLimitMs(2000);
        v.setMemoryLimitKb(262144);
        v.setOutputLimitKb(65536);
        v.setAllowedLanguages("c,cpp,java");
        v.setStatus(status);
        v.setCreatedAt(Instant.now());
        return v;
    }

    @Test
    void createInsertsProblemVersionAndTestcases() {
        var view = service.create("A+B", "求和", "1 2", "3", 1000, 131072, 32768, "c,cpp",
                List.of(new ProblemService.TestcasePayload("01", "1 2", "3"),
                        new ProblemService.TestcasePayload("02", "3 4", "7")),
                "teacher_1");

        assertThat(view.problemId()).isNotBlank();
        verify(problemMapper).insert(any(ProblemEntity.class));
        verify(versionMapper).insert(any(ProblemVersionEntity.class));
        verify(testcaseMapper, org.mockito.Mockito.times(2)).insert(any(TestcaseEntity.class));
    }

    @Test
    void createUsesDefaultLimitsWhenAbsent() {
        service.create("A+B", "求和", null, null, null, null, null, null, null, "teacher_1");

        ArgumentCaptor<ProblemVersionEntity> captor = ArgumentCaptor.forClass(ProblemVersionEntity.class);
        verify(versionMapper).insert(captor.capture());
        assertThat(captor.getValue().getTimeLimitMs()).isEqualTo(2000);
        assertThat(captor.getValue().getAllowedLanguages()).isEqualTo("c,cpp,java,python");
        assertThat(captor.getValue().getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void newVersionCopiesBaseAndIncrementsVersionNo() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId("p1");
        when(problemMapper.selectById("p1")).thenReturn(problem);
        when(versionMapper.selectById("v1")).thenReturn(version("v1", "p1", 1, "PUBLISHED"));
        when(versionMapper.selectList(any())).thenReturn(List.of(version("v1", "p1", 1, "PUBLISHED")));
        when(testcaseMapper.selectList(any())).thenReturn(List.of(tc("01", "1 2", "3")));

        var view = service.createNewVersion("p1", "v1", null, "新题面", null, null, null, null, null, null, null, "teacher_1");

        assertThat(view.versionNo()).isEqualTo(2);
        assertThat(view.statement()).isEqualTo("新题面");
        assertThat(view.status()).isEqualTo("DRAFT");
        // 未显式传测试点：复制基础版本
        ArgumentCaptor<TestcaseEntity> captor = ArgumentCaptor.forClass(TestcaseEntity.class);
        verify(testcaseMapper).insert(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("01");
    }

    @Test
    void publishSwitchesCurrentVersion() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId("p1");
        when(problemMapper.selectById("p1")).thenReturn(problem);
        when(versionMapper.selectById("v1")).thenReturn(version("v1", "p1", 1, "DRAFT"));

        var view = service.publishVersion("p1", "v1");

        assertThat(view.status()).isEqualTo("PUBLISHED");
        ArgumentCaptor<ProblemEntity> captor = ArgumentCaptor.forClass(ProblemEntity.class);
        verify(problemMapper).updateById(captor.capture());
        assertThat(captor.getValue().getCurrentVersionId()).isEqualTo("v1");
    }

    @Test
    void republishIsRejectedAsImmutable() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId("p1");
        when(problemMapper.selectById("p1")).thenReturn(problem);
        when(versionMapper.selectById("v1")).thenReturn(version("v1", "p1", 1, "PUBLISHED"));

        assertThatThrownBy(() -> service.publishVersion("p1", "v1"))
                .isInstanceOf(ProblemException.VersionImmutable.class);
    }

    @Test
    void getCurrentPublishedWithoutPublishedVersionThrows() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId("p1");
        problem.setCurrentVersionId(null);
        when(problemMapper.selectById("p1")).thenReturn(problem);

        assertThatThrownBy(() -> service.getCurrentPublished("p1"))
                .isInstanceOf(ProblemException.VersionNotFound.class);
    }

    @Test
    void getCurrentPublishedReturnsVersionWithoutTestcases() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId("p1");
        problem.setCurrentVersionId("v1");
        when(problemMapper.selectById("p1")).thenReturn(problem);
        when(versionMapper.selectById("v1")).thenReturn(version("v1", "p1", 1, "PUBLISHED"));

        var view = service.getCurrentPublished("p1");

        assertThat(view.title()).isEqualTo("A+B");
        assertThat(view.allowedLanguages()).containsExactly("c", "cpp", "java");
        // 考生视角视图不访问测试点表
        verify(testcaseMapper, never()).selectList(any());
    }

    @Test
    void listTestcasesRejectsVersionOfOtherProblem() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId("p1");
        when(problemMapper.selectById("p1")).thenReturn(problem);
        when(versionMapper.selectById("v9")).thenReturn(version("v9", "p2", 1, "DRAFT"));

        assertThatThrownBy(() -> service.listTestcases("p1", "v9"))
                .isInstanceOf(ProblemException.VersionNotFound.class);
    }

    private static TestcaseEntity tc(String name, String input, String output) {
        TestcaseEntity t = new TestcaseEntity();
        t.setId("t" + name);
        t.setVersionId("v1");
        t.setName(name);
        t.setInputContent(input);
        t.setExpectedOutput(output);
        return t;
    }
}
