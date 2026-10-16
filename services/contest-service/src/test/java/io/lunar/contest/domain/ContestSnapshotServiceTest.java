package io.lunar.contest.domain;

import io.lunar.contest.domain.dto.ContestSnapshot;
import io.lunar.contest.infra.CandidateEntity;
import io.lunar.contest.infra.CandidateMapper;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestMapper;
import io.lunar.contest.infra.ContestProblemEntity;
import io.lunar.contest.infra.ContestProblemMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 考试快照（服务间参照数据）：analytics 的读模型完全依赖它的字段完整性——
 * 少一个 contestId/studentId 或题目锁定版本，分析侧对应的那一维就永远是空的。
 *
 * 另外两条语义必须钉住：① 考生里的 REMOVED 不被服务端过滤（快照是缓存，过滤留给使用方）；
 * ② 不存在的考试返回 CONTEST_NOT_FOUND，而不是"空快照"——后者会让分析侧静默写入一堆空维度。
 */
class ContestSnapshotServiceTest {

    private final ContestMapper contestMapper = mock(ContestMapper.class);
    private final CandidateMapper candidateMapper = mock(CandidateMapper.class);
    private final ContestProblemMapper contestProblemMapper = mock(ContestProblemMapper.class);
    private final ContestSnapshotService service =
            new ContestSnapshotService(contestMapper, candidateMapper, contestProblemMapper);

    @BeforeAll
    static void initMpMetadata() {
        // LambdaQueryWrapper 解析实体列需要 TableInfo；纯 mock 测试里没有 Spring 容器，必须手动初始化
        for (Class<?> entityType : List.of(ContestEntity.class, CandidateEntity.class, ContestProblemEntity.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }

    @Test
    void snapshotCarriesContestCandidatesAndLockedProblems() {
        when(contestMapper.selectById("c1")).thenReturn(contest());
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate("stu_1", "ENROLLED")));
        when(contestProblemMapper.selectList(any())).thenReturn(List.of(lockedProblem("p1", "v1")));

        ContestSnapshot snapshot = service.snapshot("c1");

        assertThat(snapshot.contestId()).isEqualTo("c1");
        assertThat(snapshot.name()).isEqualTo("期中考试");
        assertThat(snapshot.status()).isEqualTo("RUNNING");
        assertThat(snapshot.ownerTeacherId()).isEqualTo("teacher_1");
        assertThat(snapshot.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.studentId()).isEqualTo("stu_1");
            assertThat(candidate.studentNo()).isEqualTo("20260001");
            assertThat(candidate.displayName()).isEqualTo("张三");
            assertThat(candidate.enrollmentStatus()).isEqualTo("ENROLLED");
        });
        assertThat(snapshot.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.problemId()).isEqualTo("p1");
            assertThat(problem.problemVersionId()).isEqualTo("v1");
        });
    }

    @Test
    void snapshotKeepsRemovedCandidatesWithTheirStatus() {
        when(contestMapper.selectById("c1")).thenReturn(contest());
        when(candidateMapper.selectList(any())).thenReturn(List.of(candidate("stu_2", "REMOVED")));
        when(contestProblemMapper.selectList(any())).thenReturn(List.of());

        ContestSnapshot snapshot = service.snapshot("c1");

        assertThat(snapshot.candidates()).singleElement()
                .satisfies(candidate -> assertThat(candidate.enrollmentStatus()).isEqualTo("REMOVED"));
    }

    @Test
    void unknownContestIsNotFound() {
        when(contestMapper.selectById("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.snapshot("missing"))
                .isInstanceOf(ContestException.NotFound.class)
                .extracting(exception -> ((ContestException) exception).code())
                .isEqualTo("CONTEST_NOT_FOUND");
    }

    private static ContestEntity contest() {
        ContestEntity contest = new ContestEntity();
        contest.setId("c1");
        contest.setName("期中考试");
        contest.setStatus("RUNNING");
        contest.setStartAt(Instant.parse("2026-11-01T01:00:00Z"));
        contest.setEndAt(Instant.parse("2026-11-01T03:00:00Z"));
        contest.setOwnerTeacherId("teacher_1");
        return contest;
    }

    private static CandidateEntity candidate(String studentId, String status) {
        CandidateEntity candidate = new CandidateEntity();
        candidate.setId("cand-" + studentId);
        candidate.setContestId("c1");
        candidate.setStudentId(studentId);
        candidate.setStudentNoSnapshot("20260001");
        candidate.setUsernameSnapshot("student_20260001");
        candidate.setDisplayNameSnapshot("张三");
        candidate.setStatus(status);
        return candidate;
    }

    private static ContestProblemEntity lockedProblem(String problemId, String versionId) {
        ContestProblemEntity lockedProblem = new ContestProblemEntity();
        lockedProblem.setId("cp-" + problemId);
        lockedProblem.setContestId("c1");
        lockedProblem.setProblemId(problemId);
        lockedProblem.setProblemVersionId(versionId);
        lockedProblem.setSortOrder(0);
        return lockedProblem;
    }
}
