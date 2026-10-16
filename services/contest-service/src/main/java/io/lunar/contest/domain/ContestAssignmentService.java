package io.lunar.contest.domain;

import io.lunar.common.security.AccessScope;
import io.lunar.contest.infra.CandidateEntity;
import io.lunar.contest.infra.CandidateMapper;
import io.lunar.contest.infra.ContestAssignmentEntity;
import io.lunar.contest.infra.ContestAssignmentMapper;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestMapper;
import io.lunar.contest.infra.ContestProblemEntity;
import io.lunar.contest.infra.ContestProblemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 随机题目分配（docs/exam-process.md §3）。
 *
 * 规则：老师把题目加入考试时已经锁定了版本（{@code contest_problems}）；发布时按学生生成分配结果——
 * 每位学生从题池里随机抽取 {@code problemsPerStudent} 道（缺省用全部），并保存 {@code randomSeed}。
 *
 * 种子由 (contestId, studentId) 决定，因此同一学生重算得到**同一组题**：既保证可复现
 * （考试后能回答"他当时看到的是哪些题、哪个版本"），也避免重跑把学生正在做的题换掉。
 */
@Component
public class ContestAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(ContestAssignmentService.class);

    private final ContestMapper contestMapper;
    private final ContestProblemMapper contestProblemMapper;
    private final ContestAssignmentMapper assignmentMapper;
    private final CandidateMapper candidateMapper;
    private final ContestService contestService;

    public ContestAssignmentService(ContestMapper contestMapper,
                                    ContestProblemMapper contestProblemMapper,
                                    ContestAssignmentMapper assignmentMapper,
                                    CandidateMapper candidateMapper,
                                    ContestService contestService) {
        this.contestMapper = contestMapper;
        this.contestProblemMapper = contestProblemMapper;
        this.assignmentMapper = assignmentMapper;
        this.candidateMapper = candidateMapper;
        this.contestService = contestService;
    }

    /**
     * 为全部在册考生生成分配（幂等：已有分配的学生跳过）。返回本次新增的分配条数。
     * 考试发布时调用；题池为空时不报错（老师可能先发布、后选题），只记一条 warn。
     */
    @Transactional
    public int generate(String contestId, AccessScope scope) {
        contestService.getOwned(contestId, scope);
        ContestEntity contest = contestMapper.selectById(contestId);
        if (contest == null) {
            throw new ContestException.NotFound();
        }
        List<ContestProblemEntity> pool = problemPool(contestId);
        if (pool.isEmpty()) {
            log.warn("考试没有锁定题目，跳过随机分配 contestId={}", contestId);
            return 0;
        }
        List<CandidateEntity> candidates = candidateMapper.selectList(new LambdaQueryWrapper<CandidateEntity>()
                .eq(CandidateEntity::getContestId, contestId)
                .eq(CandidateEntity::getStatus, "ENROLLED"));
        int created = 0;
        for (CandidateEntity candidate : candidates) {
            created += generateFor(contestId, candidate.getStudentId(), pool, contest.getProblemsPerStudent());
        }
        log.info("随机题目分配完成 contestId={} 在册考生={} 题池={} 新增分配={}",
                contestId, candidates.size(), pool.size(), created);
        return created;
    }

    /** 某位学生的题目分配（老师/管理员视图；考试后用于复盘）。 */
    public List<ContestAssignmentEntity> listForStudent(String contestId, String studentId, AccessScope scope) {
        contestService.getOwned(contestId, scope);
        return listForStudent(contestId, studentId);
    }

    /** 某位学生的题目分配（考生本人视图）。 */
    public List<ContestAssignmentEntity> listForStudent(String contestId, String studentId) {
        return assignmentMapper.selectList(new LambdaQueryWrapper<ContestAssignmentEntity>()
                .eq(ContestAssignmentEntity::getContestId, contestId)
                .eq(ContestAssignmentEntity::getStudentId, studentId)
                .orderByAsc(ContestAssignmentEntity::getAssignedAt));
    }

    /** 该考试是否已经生成过分配：决定提交校验走"按学生分配"还是"考试题池"。 */
    public boolean hasAssignments(String contestId) {
        return assignmentMapper.selectCount(new LambdaQueryWrapper<ContestAssignmentEntity>()
                .eq(ContestAssignmentEntity::getContestId, contestId)) > 0;
    }

    /** 该学生在这道题上被分配的版本（提交校验用）；没分配给他就按"不存在"处理。 */
    public ContestAssignmentEntity requireAssigned(String contestId, String studentId, String problemId) {
        ContestAssignmentEntity assignment = assignmentMapper.selectOne(
                new LambdaQueryWrapper<ContestAssignmentEntity>()
                        .eq(ContestAssignmentEntity::getContestId, contestId)
                        .eq(ContestAssignmentEntity::getStudentId, studentId)
                        .eq(ContestAssignmentEntity::getProblemId, problemId)
                        .last("limit 1"));
        if (assignment == null) {
            throw new ContestException.ProblemNotInContest();
        }
        return assignment;
    }

    private int generateFor(String contestId, String studentId, List<ContestProblemEntity> pool,
                            Integer problemsPerStudent) {
        if (!listForStudent(contestId, studentId).isEmpty()) {
            return 0;
        }
        long seed = seedOf(contestId, studentId);
        List<ContestProblemEntity> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, new Random(seed));
        int take = (problemsPerStudent == null || problemsPerStudent <= 0 || problemsPerStudent > shuffled.size())
                ? shuffled.size() : problemsPerStudent;
        for (ContestProblemEntity problem : shuffled.subList(0, take)) {
            ContestAssignmentEntity assignment = new ContestAssignmentEntity();
            assignment.setId(UUID.randomUUID().toString());
            assignment.setContestId(contestId);
            assignment.setStudentId(studentId);
            assignment.setProblemId(problem.getProblemId());
            assignment.setProblemVersionId(problem.getProblemVersionId());
            assignment.setRandomSeed(seed);
            assignment.setAssignmentVersion(1);
            assignment.setAssignedAt(Instant.now());
            assignmentMapper.insert(assignment);
        }
        return take;
    }

    private List<ContestProblemEntity> problemPool(String contestId) {
        return contestProblemMapper.selectList(new LambdaQueryWrapper<ContestProblemEntity>()
                .eq(ContestProblemEntity::getContestId, contestId)
                .orderByAsc(ContestProblemEntity::getSortOrder)
                .orderByAsc(ContestProblemEntity::getCreatedAt));
    }

    /**
     * 分配种子：由 (考试, 学生) 稳定推导，不用随机数生成器——重跑必须得到同一组题。
     * {@code String.hashCode} 的算法由规范固定，跨进程/跨版本一致，足以满足"可复现"的要求。
     */
    private static long seedOf(String contestId, String studentId) {
        return (contestId + "|" + studentId).hashCode() & 0xffffffffL;
    }
}
