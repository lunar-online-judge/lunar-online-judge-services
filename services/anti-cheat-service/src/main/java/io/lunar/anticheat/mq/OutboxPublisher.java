package io.lunar.anticheat.mq;

import io.lunar.anticheat.infra.OutboxEventEntity;
import io.lunar.anticheat.infra.OutboxEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Outbox 发布器：lunar-risk-event-created 等事件投递 RocketMQ（gateway 告警的未来消费方）。 */
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
        var pending = outboxMapper.selectList(new LambdaQueryWrapper<OutboxEventEntity>()
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
            } catch (Exception e) {
                log.warn("Outbox publish failed eventId={} type={}：留待下一轮重试",
                        event.getEventId(), event.getEventType());
            }
        }
    }
}
