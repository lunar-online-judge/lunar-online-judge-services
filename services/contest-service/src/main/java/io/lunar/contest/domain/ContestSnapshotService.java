package io.lunar.contest.domain;

import io.lunar.contest.domain.dto.ContestSnapshot;
import io.lunar.contest.domain.dto.ContestSnapshotCandidate;
import io.lunar.contest.domain.dto.ContestSnapshotProblem;
import io.lunar.contest.infra.CandidateEntity;
import io.lunar.contest.infra.CandidateMapper;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestMapper;
import io.lunar.contest.infra.ContestProblemEntity;
import io.lunar.contest.infra.ContestProblemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 考试快照的组装（服务间参照数据，供 analytics-service 的读模型定时拉取）。
 *
 * 单独成一个组件而不是塞进 {@link ContestService}：它要同时读三张表（考试 / 名单 / 锁定题目），
 * 而 ContestService 只拥有考试主表——把跨表组装混进去会让那个类同时承担"生命周期规则"和"参照数据投影"两件事。
 *
 * 不经 {@link ContestProblemService#list} 取题目清单：那个方法要求教师归属范围（BE-01），
 * 而本接口的调用方是服务身份，没有教师范围可言。
 */
@Component
public class ContestSnapshotService {

    private final ContestMapper contestMapper;
    private final CandidateMapper candidateMapper;
    private final ContestProblemMapper contestProblemMapper;

    public ContestSnapshotService(ContestMapper contestMapper, CandidateMapper candidateMapper,
                                  ContestProblemMapper contestProblemMapper) {
        this.contestMapper = contestMapper;
        this.candidateMapper = candidateMapper;
        this.contestProblemMapper = contestProblemMapper;
    }

    /** 组装快照；考试不存在时抛 {@link ContestException.NotFound}（404，不泄露其它考试的名单）。 */
    public ContestSnapshot snapshot(String contestId) {
        ContestEntity contest = contestMapper.selectById(contestId);
        if (contest == null) {
            throw new ContestException.NotFound();
        }
        return new ContestSnapshot(contest.getId(), contest.getName(), contest.getStatus(),
                contest.getStartAt(), contest.getEndAt(), contest.getOwnerTeacherId(),
                candidatesOf(contestId), problemsOf(contestId));
    }

    /** 名单按学号快照排序：分析侧的"学生"维度需要稳定顺序，否则同一份数据两次拉取的行序不同。 */
    private List<ContestSnapshotCandidate> candidatesOf(String contestId) {
        return candidateMapper.selectList(new LambdaQueryWrapper<CandidateEntity>()
                        .eq(CandidateEntity::getContestId, contestId)
                        .orderByAsc(CandidateEntity::getStudentNoSnapshot))
                .stream()
                .map(ContestSnapshotService::toCandidate)
                .toList();
    }

    /** 题目按 sortOrder 再按锁定时间排序：与 {@code ContestProblemService.list} 的排序保持一致。 */
    private List<ContestSnapshotProblem> problemsOf(String contestId) {
        return contestProblemMapper.selectList(new LambdaQueryWrapper<ContestProblemEntity>()
                        .eq(ContestProblemEntity::getContestId, contestId)
                        .orderByAsc(ContestProblemEntity::getSortOrder)
                        .orderByAsc(ContestProblemEntity::getCreatedAt))
                .stream()
                .map(ContestSnapshotService::toProblem)
                .toList();
    }

    private static ContestSnapshotCandidate toCandidate(CandidateEntity candidate) {
        return new ContestSnapshotCandidate(candidate.getStudentId(), candidate.getStudentNoSnapshot(),
                candidate.getUsernameSnapshot(), candidate.getDisplayNameSnapshot(), candidate.getStatus());
    }

    private static ContestSnapshotProblem toProblem(ContestProblemEntity lockedProblem) {
        return new ContestSnapshotProblem(lockedProblem.getProblemId(), lockedProblem.getProblemVersionId(),
                lockedProblem.getSortOrder());
    }
}
