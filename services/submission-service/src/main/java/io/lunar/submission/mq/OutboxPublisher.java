package io.lunar.submission.mq;

import io.lunar.submission.infra.OutboxEventEntity;
import io.lunar.submission.infra.OutboxEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Outbox 发布器（docs/architecture.md §8 第 4 步）：扫描未发布事件行，
 * 按事件类型投递到对应 Topic（消息体 = 事件 payload JSON），成功后标记 published_at。
 * 发送失败留在下一轮重试；消费者按 eventId 幂等。
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventMapper outboxMapper;
    private final RocketMQTemplate rocketMQTemplate;

    public OutboxPublisher(OutboxEventMapper outboxMapper, RocketMQTemplate rocketMQTemplate) {
        this.outboxMapper = outboxMapper;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.publish-interval-ms:2000}")
    public void publishPending() {
        List<OutboxEventEntity> pending = outboxMapper.selectList(new LambdaQueryWrapper<OutboxEventEntity>()
                .isNull(OutboxEventEntity::getPublishedAt)
                .orderByAsc(OutboxEventEntity::getCreatedAt)
                .last("limit 100"));
        for (OutboxEventEntity event : pending) {
            try {
                rocketMQTemplate.syncSend(event.getEventType(),
                        MessageBuilder.withPayload(event.getPayload())
                                .setHeader("KEYS", event.getEventId())
                                .setHeader("traceId", event.getTraceId())
                                .build());
                event.setPublishedAt(Instant.now());
                outboxMapper.updateById(event);
                log.info("Outbox published eventId={} type={}", event.getEventId(), event.getEventType());
            } catch (Exception exception) {
                log.warn("Outbox publish failed eventId={} type={}：留待下一轮重试",
                        event.getEventId(), event.getEventType(), exception);
            }
        }
    }
}
