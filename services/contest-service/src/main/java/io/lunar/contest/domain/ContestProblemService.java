package io.lunar.contest.domain;

import io.lunar.common.security.AccessScope;
import io.lunar.contest.infra.ContestProblemEntity;
import io.lunar.contest.infra.ContestProblemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 考试题目清单与版本锁定（docs/exam-process.md §3、BACKEND_IMPROVEMENT_PLAN BE-04）：
 * 老师把题目加入考试时必须指定**版本**，之后全局再发布新版本不会影响这场考试——
 * 提交、判题命令与测试点读取都用这个锁定版本，而不是题目的"当前发布版本"。
 *
 * 随机分配到学生是后续能力；本服务先表达"这场考试用这些题目的哪个版本"。
 * 管理端操作限考试归属者或 ADMIN（BE-01 的 {@link AccessScope}）。
 */
@Component
public class ContestProblemService {

    private final ContestProblemMapper contestProblemMapper;
    private final ContestService contestService;

    public ContestProblemService(ContestProblemMapper contestProblemMapper, ContestService contestService) {
        this.contestProblemMapper = contestProblemMapper;
        this.contestService = contestService;
    }

    /** 加入题目并锁定版本；同一考试同一题目重复加入时覆盖版本（老师改主意不必先删）。 */
    public ContestProblemEntity lock(String contestId, String problemId, String problemVersionId, AccessScope scope) {
        contestService.getOwned(contestId, scope);
        ContestProblemEntity existing = find(contestId, problemId);
        if (existing != null) {
            existing.setProblemVersionId(problemVersionId);
            contestProblemMapper.updateById(existing);
            return existing;
        }
        ContestProblemEntity entity = new ContestProblemEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setContestId(contestId);
        entity.setProblemId(problemId);
        entity.setProblemVersionId(problemVersionId);
        entity.setSortOrder(0);
        entity.setCreatedAt(Instant.now());
        contestProblemMapper.insert(entity);
        return entity;
    }

    public List<ContestProblemEntity> list(String contestId, AccessScope scope) {
        contestService.getOwned(contestId, scope);
        return contestProblemMapper.selectList(new LambdaQueryWrapper<ContestProblemEntity>()
                .eq(ContestProblemEntity::getContestId, contestId)
                .orderByAsc(ContestProblemEntity::getSortOrder)
                .orderByAsc(ContestProblemEntity::getCreatedAt));
    }

    public void unlock(String contestId, String problemId, AccessScope scope) {
        contestService.getOwned(contestId, scope);
        ContestProblemEntity existing = find(contestId, problemId);
        if (existing == null) {
            throw new ContestException.NotFound();
        }
        contestProblemMapper.deleteById(existing.getId());
    }

    /**
     * 该考试锁定版本的查询（提交校验用）：题目不在考试里时抛 {@link ContestException.ProblemNotInContest}。
     * 这是"题目属于竞赛"这一项校验的唯一事实源。
     */
    public ContestProblemEntity requireLocked(String contestId, String problemId) {
        ContestProblemEntity locked = find(contestId, problemId);
        if (locked == null) {
            throw new ContestException.ProblemNotInContest();
        }
        return locked;
    }

    private ContestProblemEntity find(String contestId, String problemId) {
        return contestProblemMapper.selectOne(new LambdaQueryWrapper<ContestProblemEntity>()
                .eq(ContestProblemEntity::getContestId, contestId)
                .eq(ContestProblemEntity::getProblemId, problemId)
                .last("limit 1"));
    }
}
