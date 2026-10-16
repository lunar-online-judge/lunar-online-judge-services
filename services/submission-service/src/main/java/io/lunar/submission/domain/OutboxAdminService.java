package io.lunar.submission.domain;

import io.lunar.submission.infra.OutboxEventEntity;
import io.lunar.submission.infra.OutboxEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 死信的运维操作（BACKEND_IMPROVEMENT_PLAN BE-07：退避与死信之外还要有"人工恢复"）。
 *
 * 投递连续失败到上限的事件不再自动重试，只能由管理员在这里查看并重放——
 * 否则"转死信"就只是把问题藏起来，事件永远到不了消费端。
 */
@Component
public class OutboxAdminService {

    private static final Logger log = LoggerFactory.getLogger(OutboxAdminService.class);

    /** 单次最多返回的死信条数：这是排障视图，不是数据导出通道。 */
    private static final int DEAD_LETTER_PAGE_LIMIT = 100;

    private final OutboxEventMapper outboxMapper;

    public OutboxAdminService(OutboxEventMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    /** 死信列表（最近进入死信的在前）。 */
    public List<OutboxEventEntity> listDeadLetters() {
        return outboxMapper.selectList(new LambdaQueryWrapper<OutboxEventEntity>()
                .isNotNull(OutboxEventEntity::getDeadLetteredAt)
                .orderByDesc(OutboxEventEntity::getDeadLetteredAt)
                .last("limit " + DEAD_LETTER_PAGE_LIMIT));
    }

    /**
     * 人工重放：清空 attempts / next_attempt_at / dead_lettered_at，让发布器重新投递。
     * 幂等：对未死信或已投递的事件调用只是把这三个字段清零，不会重复投递（发布器按 published_at 判断）。
     */
    public void replay(String eventId) {
        OutboxEventEntity event = outboxMapper.selectOne(new LambdaQueryWrapper<OutboxEventEntity>()
                .eq(OutboxEventEntity::getEventId, eventId)
                .last("limit 1"));
        if (event == null) {
            throw new SubmissionException.NotFound();
        }
        event.setAttempts(0);
        event.setNextAttemptAt(null);
        event.setDeadLetteredAt(null);
        outboxMapper.updateById(event);
        log.info("死信重放 eventId={} type={} published={}", eventId, event.getEventType(),
                event.getPublishedAt() != null);
    }
}
