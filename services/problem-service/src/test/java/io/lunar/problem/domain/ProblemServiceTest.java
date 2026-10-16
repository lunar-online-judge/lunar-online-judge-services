package io.lunar.problem.domain;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.lunar.common.security.AccessScope;
import io.lunar.problem.config.TestdataProperties;
import io.lunar.problem.domain.dto.ProblemView;
import io.lunar.problem.domain.dto.TestcasePayload;
import io.lunar.problem.domain.dto.TestcaseView;
import io.lunar.problem.domain.dto.VersionView;
import io.lunar.problem.infra.ProblemEntity;
import io.lunar.problem.infra.ProblemMapper;
import io.lunar.problem.infra.ProblemVersionEntity;
import io.lunar.problem.infra.ProblemVersionMapper;
import io.lunar.problem.infra.TestcaseEntity;
import io.lunar.problem.infra.TestcaseMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProblemServiceTest {

    private final ProblemMapper problemMapper = mock(ProblemMapper.class);
    private final ProblemVersionMapper versionMapper = mock(ProblemVersionMapper.class);
    private final TestcaseMapper testcaseMapper = mock(TestcaseMapper.class);
    private final TestdataProperties testdataProperties = testdataProperties();
    private final ProblemService service = new ProblemService(problemMapper, versionMapper, testcaseMapper,
            testdataProperties);

    private static TestdataProperties testdataProperties() {
        TestdataProperties properties = new TestdataProperties();
        properties.setMaxBytes(1024 * 1024);
        return properties;
    }

    @BeforeAll
    static void initMpMetadata() {
        // MyBatis-Plus 的 Lambda 条件构造需要实体元数据，纯单测下手工初始化
        for (Class<?> entityType : List.of(ProblemVersionEntity.class, TestcaseEntity.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }

    private static ProblemVersionEntity problemVersion(String id, String problemId, int versionNo, String status) {
        ProblemVersionEntity version = new ProblemVersionEntity();
        version.setId(id);
        version.setProblemId(problemId);
        version.setVersionNo(versionNo);
        version.setTitle("A+B");
        version.setStatement("求和");
        version.setTimeLimitMs(2000);
        version.setMemoryLimitKb(262144);
        version.setOutputLimitKb(65536);
        version.setAllowedLanguages("c,cpp,java");
        version.setStatus(status);
        version.setCreatedAt(Instant.now());
        return version;
    }

    private static TestcaseEntity testcase(String name, String input, String output) {
        TestcaseEntity testcase = new TestcaseEntity();
        testcase.setId("t" + name);
        testcase.setVersionId("v1");
        testcase.setName(name);
        testcase.setInputContent(input);
        testcase.setExpectedOutput(output);
        return testcase;
    }

    @Test
    void createInsertsProblemVersionAndTestcases() {
        ProblemView view = service.create("A+B", "求和", "1 2", "3", 1000, 131072, 32768, "c,cpp",
                List.of(new TestcasePayload("01", "1 2", "3"),
                        new TestcasePayload("02", "3 4", "7")),
                "teacher_1");

        assertThat(view.problemId()).isNotBlank();
        verify(problemMapper).insert(any(ProblemEntity.class));
        verify(versionMapper).insert(any(ProblemVersionEntity.class));
        verify(testcaseMapper, times(2)).insert(any(TestcaseEntity.class));
    }

    @Test
    void createUsesDefaultLimitsWhenAbsent() {
        service.create("A+B", "求和", null, null, null, null, null, null, null, "teacher_1");

        ArgumentCaptor<ProblemVersionEntity> captor = ArgumentCaptor.forClass(ProblemVersionEntity.class);
        verify(versionMapper).insert(captor.capture());
        assertThat(captor.getValue().getTimeLimitMs()).isEqualTo(2000);
        assertThat(captor.getValue().getAllowedLanguages()).isEqualTo("c,cpp,java,python,javascript,typescript");
        assertThat(captor.getValue().getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void newVersionCopiesBaseAndIncrementsVersionNo() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId("p1");
        when(problemMapper.selectById("p1")).thenReturn(problem);
        when(versionMapper.selectById("v1")).thenReturn(problemVersion("v1", "p1", 1, "PUBLISHED"));
        when(versionMapper.selectList(any())).thenReturn(List.of(problemVersion("v1", "p1", 1, "PUBLISHED")));
        when(testcaseMapper.selectList(any())).thenReturn(List.of(testcase("01", "1 2", "3")));

        VersionView view = service.createNewVersion("p1", "v1", null, "新题面",
                null, null, null, null, null, null, null, "teacher_1");

        assertThat(view.versionNo()).isEqualTo(2);
        assertThat(view.statement()).isEqualTo("新题面");
        assertThat(view.status()).isEqualTo("DRAFT");
        // 未显式传测试点：复制基础版本
        ArgumentCaptor<TestcaseEntity> captor = ArgumentCaptor.forClass(TestcaseEntity.class);
        verify(testcaseMapper).insert(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("01");
    }

    @Test
    void newVersionFallsBackToCurrentVersionWhenBaseAbsent() {
        // baseVersionId 缺省时基于题目当前版本（DTO 注释与 docs/api.md 的约定）
        ProblemEntity problem = new ProblemEntity();
        problem.setId("p1");
        problem.setCurrentVersionId("v1");
        when(problemMapper.selectById("p1")).thenReturn(problem);
        when(versionMapper.selectById("v1")).thenReturn(problemVersion("v1", "p1", 1, "PUBLISHED"));
        when(versionMapper.selectList(any())).thenReturn(List.of(problemVersion("v1", "p1", 1, "PUBLISHED")));
        when(testcaseMapper.selectList(any())).thenReturn(List.of(testcase("01", "1 2", "3")));

        VersionView view = service.createNewVersion("p1", null, null, null,
                null, null, null, null, null, null, null, "teacher_1");

        assertThat(view.versionNo()).isEqualTo(2);
        verify(versionMapper).selectById("v1");
        verify(testcaseMapper).insert(any(TestcaseEntity.class));
    }

    @Test
    void judgingTestcaseQueryRejectsOversizedTestdata() {
        // 测试点要随 submission.created 事件进 MQ：超限必须在上游拦住，否则消息永远发不出去
        ProblemEntity problem = new ProblemEntity();
        problem.setId("p1");
        when(problemMapper.selectById("p1")).thenReturn(problem);
        when(versionMapper.selectById("v1")).thenReturn(problemVersion("v1", "p1", 1, "PUBLISHED"));
        when(testcaseMapper.selectList(any())).thenReturn(List.of(testcase("big", "x".repeat(2048), "y")));
        testdataProperties.setMaxBytes(1024);

        assertThatThrownBy(() -> service.listTestcasesForJudging("p1", "v1"))
                .isInstanceOf(ProblemException.TestdataTooLarge.class);
    }

    /** BE-01：题目归属不在调用者范围内时按"不存在"处理（404 隐藏存在）。 */
    @Test
    void requireOwnedRejectsProblemOfAnotherTeacher() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId("p1");
        problem.setOwnerTeacherId("teacher_b");
        when(problemMapper.selectById("p1")).thenReturn(problem);

        assertThatThrownBy(() -> service.requireOwned("p1", new AccessScope(false, "teacher_a")))
                .isInstanceOf(ProblemException.NotFound.class);
    }

    @Test
    void requireOwnedAllowsOwnerAndAdmin() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId("p1");
        problem.setOwnerTeacherId("teacher_a");
        when(problemMapper.selectById("p1")).thenReturn(problem);

        service.requireOwned("p1", new AccessScope(false, "teacher_a"));
        service.requireOwned("p1", new AccessScope(true, "admin_1"));
    }

    @Test
    void judgingTestcaseQueryReturnsTestcasesWithinLimit() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId("p1");
        when(problemMapper.selectById("p1")).thenReturn(problem);
        when(versionMapper.selectById("v1")).thenReturn(problemVersion("v1", "p1", 1, "PUBLISHED"));
        when(testcaseMapper.selectList(any())).thenReturn(List.of(testcase("01", "1 2", "3")));

        List<TestcaseView> testcases = service.listTestcasesForJudging("p1", "v1");

        assertThat(testcases).hasSize(1);
        assertThat(testcases.get(0).name()).isEqualTo("01");
        assertThat(testcases.get(0).expectedOutput()).isEqualTo("3");
    }

    @Test
    void publishSwitchesCurrentVersion() {
        ProblemEntity problem = new ProblemEntity();
        problem.setId("p1");
        when(problemMapper.selectById("p1")).thenReturn(problem);
        when(versionMapper.selectById("v1")).thenReturn(problemVersion("v1", "p1", 1, "DRAFT"));

        VersionView view = service.publishVersion("p1", "v1");

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
        when(versionMapper.selectById("v1")).thenReturn(problemVersion("v1", "p1", 1, "PUBLISHED"));

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
        when(versionMapper.selectById("v1")).thenReturn(problemVersion("v1", "p1", 1, "PUBLISHED"));

        VersionView view = service.getCurrentPublished("p1");

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
        when(versionMapper.selectById("v9")).thenReturn(problemVersion("v9", "p2", 1, "DRAFT"));

        assertThatThrownBy(() -> service.listTestcases("p1", "v9"))
                .isInstanceOf(ProblemException.VersionNotFound.class);
    }
}
