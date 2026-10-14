package io.lunar.contest.domain;

import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 竞赛生命周期编排：创建草稿、查询、状态流转。
 * 流转使用「where status = 期望前态」的条件更新防并发双审：两管理员同时发布只有一个成功。
 */
@Component
public class ContestService {

    /** 管理端列表分页：items 为当前页竞赛。 */
    public record ContestPage(List<ContestEntity> items, long total, long page, long pageSize) {}

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

    /** 分页列表（管理端）：按创建时间倒序，可选状态过滤。 */
    public ContestPage list(String status, long page, long pageSize) {
        Page<ContestEntity> pageRequest = new Page<>(page, pageSize);
        LambdaQueryWrapper<ContestEntity> query = new LambdaQueryWrapper<ContestEntity>()
                .orderByDesc(ContestEntity::getCreatedAt);
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
