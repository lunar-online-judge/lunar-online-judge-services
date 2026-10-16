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
import java.util.List;

/** Outbox 发布器：lunar-risk-event-created 等事件投递 RocketMQ（gateway 告警的未来消费方）。 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** 单轮发布的最大事件数，避免一次拉取过多拖垮调度线程。 */
    private static final int PUBLISH_BATCH_SIZE = 100;

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
                .last("limit " + PUBLISH_BATCH_SIZE));
        for (OutboxEventEntity event : pending) {
            try {
                MessageBuilder<String> message = MessageBuilder.withPayload(event.getPayload())
                        .setHeader("KEYS", event.getEventId());
                // traceId 为空时不能设该头：RocketMQ 拒绝空属性值（"property can not be null or blank string"），
                // 设了整条消息就发不出去，事件会永远卡在 Outbox 里重试
                if (event.getTraceId() != null && !event.getTraceId().isBlank()) {
                    message.setHeader("traceId", event.getTraceId());
                }
                rocketMQTemplate.syncSend(event.getEventType(), message.build());
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
