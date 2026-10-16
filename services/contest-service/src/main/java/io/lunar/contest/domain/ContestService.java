package io.lunar.contest.domain;

import io.lunar.common.security.AccessScope;
import io.lunar.contest.domain.dto.ContestPage;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 竞赛生命周期编排：创建草稿、查询、状态流转。
 * 流转使用「where status = 期望前态」的条件更新防并发双审：两管理员同时发布只有一个成功。
 */
@Component
public class ContestService {

    private final ContestMapper contestMapper;

    public ContestService(ContestMapper contestMapper) {
        this.contestMapper = contestMapper;
    }

    public ContestEntity create(String name, String description, Instant startAt, Instant endAt, String createdBy) {
        ContestEntity contest = new ContestEntity();
        contest.setId(UUID.randomUUID().toString());
        contest.setName(name);
        contest.setDescription(description);
        contest.setStatus(ContestStatus.DRAFT.name());
        contest.setStartAt(startAt);
        contest.setEndAt(endAt);
        contest.setCreatedBy(createdBy);
        // 创建者即归属者（BE-01）：created_by 是事实，owner 是权限；转移归属是独立审核动作
        contest.setOwnerTeacherId(createdBy);
        contest.setCreatedAt(Instant.now());
        contest.setUpdatedAt(Instant.now());
        contestMapper.insert(contest);
        return contest;
    }

    public ContestEntity get(String id) {
        ContestEntity contest = contestMapper.selectById(id);
        if (contest == null) {
            throw new ContestException.NotFound();
        }
        return contest;
    }

    /**
     * 按授权范围取考试（BE-01）：越权一律按"不存在"处理（404 隐藏存在），
     * 避免用 403 泄露"这个 ID 确实存在"。学生侧入口（进入考试/心跳/公告）不走本方法。
     */
    public ContestEntity getOwned(String id, AccessScope scope) {
        ContestEntity contest = get(id);
        if (!scope.allows(contest.getOwnerTeacherId())) {
            throw new ContestException.NotFound();
        }
        return contest;
    }

    /** 分页列表（管理端）：先叠加服务端范围（老师只看自己的），再套用户筛选，total 同样落在范围内。 */
    public ContestPage list(String status, long page, long pageSize, AccessScope scope) {
        Page<ContestEntity> pageRequest = new Page<>(page, pageSize);
        LambdaQueryWrapper<ContestEntity> query = new LambdaQueryWrapper<ContestEntity>()
                .orderByDesc(ContestEntity::getCreatedAt);
        if (!scope.global()) {
            query.eq(ContestEntity::getOwnerTeacherId, scope.ownerId());
        }
        if (status != null && !status.isBlank()) {
            query.eq(ContestEntity::getStatus, status);
        }
        Page<ContestEntity> result = contestMapper.selectPage(pageRequest, query);
        return new ContestPage(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 提交管理员审核：DRAFT -> REVIEWING（老师/管理员）。 */
    public ContestEntity submitForReview(String id) {
        return doTransition(get(id), ContestStatus.REVIEWING);
    }

    /**
     * 管理员审核通过并发布：REVIEWING -> PUBLISHED。
     * 先校验状态合法性（如草稿直接发布报非法流转），再校验发布条件（exam-process §1：时间窗口等）。
     */
    public ContestEntity publish(String id) {
        ContestEntity current = get(id);
        ContestStatus from = ContestStatus.valueOf(current.getStatus());
        if (!from.canTransitionTo(ContestStatus.PUBLISHED)) {
            throw new ContestException.InvalidTransition(from, ContestStatus.PUBLISHED);
        }
        if (current.getStartAt() == null || current.getEndAt() == null) {
            throw new ContestException.PublishIncomplete("考试时间窗口");
        }
        return doTransition(current, ContestStatus.PUBLISHED);
    }

    /** 管理员归档：FINISHED -> ARCHIVED（低频管理动作，不自动执行）。 */
    public ContestEntity archive(String id) {
        return doTransition(get(id), ContestStatus.ARCHIVED);
    }

    private ContestEntity doTransition(ContestEntity current, ContestStatus target) {
        ContestStatus from = ContestStatus.valueOf(current.getStatus());
        if (!from.canTransitionTo(target)) {
            throw new ContestException.InvalidTransition(from, target);
        }
        LambdaUpdateWrapper<ContestEntity> update = new LambdaUpdateWrapper<ContestEntity>()
                .eq(ContestEntity::getId, current.getId())
                .eq(ContestEntity::getStatus, from.name())
                .set(ContestEntity::getStatus, target.name())
                .set(ContestEntity::getUpdatedAt, Instant.now());
        if (contestMapper.update(null, update) == 0) {
            // 并发流转：前态已被其他请求改走
            throw new ContestException.InvalidTransition(from, target);
        }
        current.setStatus(target.name());
        return current;
    }
}
