package io.lunar.analytics.domain;

import io.lunar.analytics.infra.ContestCandidateRefMapper;
import io.lunar.analytics.infra.ContestProblemRefMapper;
import io.lunar.analytics.infra.ContestRefMapper;
import io.lunar.analytics.infra.ContestCandidateRefEntity;
import io.lunar.analytics.infra.ContestProblemRefEntity;
import io.lunar.analytics.infra.dto.ContestSnapshotCandidateView;
import io.lunar.analytics.infra.dto.ContestSnapshotProblemView;
import io.lunar.analytics.infra.dto.ContestSnapshotView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一份考试快照**整批**替换进三张参照表。
 *
 * 为什么单独成 Bean：整批替换必须在一个事务里（先删后插），而它的调用方
 * （{@link ContestRefRefreshService}）需要先做一次 HTTP 取数——把事务开在含 HTTP 调用的方法上，
 * 会让数据库连接在整个上游往返期间被占着。拆开之后：HTTP 在事务外，写库全部在这里。
 *
 * 关于"为什么名单不过滤 REMOVED"：见 ContestCandidateRefMapper 的类注释——
 * 参照表是一份忠实副本，口径由读模型决定。
 */
@Service
public class ContestRefStore {

    private static final Logger log = LoggerFactory.getLogger(ContestRefStore.class);

    private final ContestRefMapper contestRefMapper;
    private final ContestProblemRefMapper contestProblemRefMapper;
    private final ContestCandidateRefMapper contestCandidateRefMapper;

    public ContestRefStore(ContestRefMapper contestRefMapper,
                           ContestProblemRefMapper contestProblemRefMapper,
                           ContestCandidateRefMapper contestCandidateRefMapper) {
        this.contestRefMapper = contestRefMapper;
        this.contestProblemRefMapper = contestProblemRefMapper;
        this.contestCandidateRefMapper = contestCandidateRefMapper;
    }

    /** 缓存是否还算新鲜（判定在数据库里做，理由见 ContestRefMapper#countFresh）。 */
    public boolean isFresh(String contestId, long maxAgeSeconds) {
        return contestRefMapper.countFresh(contestId, maxAgeSeconds) > 0;
    }

    /**
     * 用快照整体替换该考试的参照数据。
     *
     * 题目与名单是"先删后插"（上游的移出操作必须能反映到这里），竞赛元数据是 upsert。
     * 三张表同事务：否则会出现"竞赛名已更新、题目清单还是旧的"这种自相矛盾的中间态被读到。
     */
    @Transactional
    public void apply(ContestSnapshotView snapshot) {
        contestRefMapper.upsert(snapshot.contestId(), snapshot.name(), snapshot.status(),
                snapshot.startAt(), snapshot.endAt(), snapshot.ownerTeacherId());
        replaceProblems(snapshot);
        replaceCandidates(snapshot);
        log.info("参照数据已刷新：contestId={} problems={} candidates={}",
                snapshot.contestId(), sizeOf(snapshot.problems()), sizeOf(snapshot.candidates()));
    }

    private void replaceProblems(ContestSnapshotView snapshot) {
        contestProblemRefMapper.deleteByContest(snapshot.contestId());
        List<ContestSnapshotProblemView> problems = snapshot.problems();
        if (problems == null || problems.isEmpty()) {
            return;
        }
        List<ContestProblemRefEntity> rows = new ArrayList<>(problems.size());
        for (ContestSnapshotProblemView problem : problems) {
            ContestProblemRefEntity row = new ContestProblemRefEntity();
            row.setContestId(snapshot.contestId());
            row.setProblemId(problem.problemId());
            row.setProblemVersionId(problem.problemVersionId());
            row.setSortOrder(problem.sortOrder());
            rows.add(row);
        }
        contestProblemRefMapper.insertBatch(rows);
    }

    private void replaceCandidates(ContestSnapshotView snapshot) {
        contestCandidateRefMapper.deleteByContest(snapshot.contestId());
        List<ContestSnapshotCandidateView> candidates = snapshot.candidates();
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        List<ContestCandidateRefEntity> rows = new ArrayList<>(candidates.size());
        for (ContestSnapshotCandidateView candidate : candidates) {
            ContestCandidateRefEntity row = new ContestCandidateRefEntity();
            row.setContestId(snapshot.contestId());
            row.setStudentId(candidate.studentId());
            row.setStudentNo(candidate.studentNo());
            row.setUsername(candidate.username());
            row.setDisplayName(candidate.displayName());
            row.setEnrollmentStatus(candidate.enrollmentStatus());
            rows.add(row);
        }
        contestCandidateRefMapper.insertBatch(rows);
    }

    private static int sizeOf(List<?> items) {
        return items == null ? 0 : items.size();
    }
}
