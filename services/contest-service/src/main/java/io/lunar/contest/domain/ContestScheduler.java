package io.lunar.contest.domain;

import io.lunar.common.audit.AuditRecorder;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 考试状态的时间窗口驱动推进（docs/exam-process.md §1/§4）：
 * PUBLISHED 且到达开始时间 → RUNNING；RUNNING 且到达结束时间 → FINISHED。
 * 条件更新（where status = 前态）保证多实例调度幂等；无开始/结束时间的考试不参与推进。
 * ARCHIVED 由管理员手动归档（低频管理动作），不在调度范围。
 * 状态变更事件（exam started/finished 推送 gateway）随事件总线阶段接入。
 * 审计：状态推进写 audit-service 账本——定时任务没有用户令牌，用服务身份令牌（role=SERVICE）。
 */
@Component
public class ContestScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContestScheduler.class);

    private final ContestMapper contestMapper;
    private final AuditRecorder auditRecorder;

    public ContestScheduler(ContestMapper contestMapper, AuditRecorder auditRecorder) {
        this.contestMapper = contestMapper;
        this.auditRecorder = auditRecorder;
    }

    @Scheduled(fixedDelayString = "${exam.status.sweep-interval-ms:30000}")
    public void advanceByTimeWindow() {
        Instant now = Instant.now();

        int started = contestMapper.update(null, new LambdaUpdateWrapper<ContestEntity>()
                .eq(ContestEntity::getStatus, ContestStatus.PUBLISHED.name())
                .isNotNull(ContestEntity::getStartAt)
                .le(ContestEntity::getStartAt, now)
                .set(ContestEntity::getStatus, ContestStatus.RUNNING.name())
                .set(ContestEntity::getUpdatedAt, now));
        if (started > 0) {
            log.info("AUDIT event=EXAM_STARTED count={}", started);
            // 定时任务没有用户令牌 → 走服务身份（账本 actor=contest-service、role=SERVICE）
            auditRecorder.record("EXAM_STARTED", "CONTEST", null, null,
                    "按时间窗口自动开始 " + started + " 场考试", null, null);
        }

        int finished = contestMapper.update(null, new LambdaUpdateWrapper<ContestEntity>()
                .eq(ContestEntity::getStatus, ContestStatus.RUNNING.name())
                .isNotNull(ContestEntity::getEndAt)
                .le(ContestEntity::getEndAt, now)
                .set(ContestEntity::getStatus, ContestStatus.FINISHED.name())
                .set(ContestEntity::getUpdatedAt, now));
        if (finished > 0) {
            log.info("AUDIT event=EXAM_FINISHED count={}", finished);
            auditRecorder.record("EXAM_FINISHED", "CONTEST", null, null,
                    "按时间窗口自动结束 " + finished + " 场考试", null, null);
        }
    }
}
