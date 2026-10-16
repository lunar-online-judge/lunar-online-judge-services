package io.lunar.submission.mq;

import io.lunar.common.event.EventEnvelope;
import io.lunar.submission.infra.OutboxEventEntity;
import io.lunar.submission.infra.OutboxEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Outbox 发布器（docs/architecture.md §8 第 4 步）：扫描未发布事件行，
 * 按事件类型投递到对应 Topic（消息体 = 完整事件信封 JSON，见 docs/api.md"事件信封"），
 * 成功后标记 published_at。发送失败留在下一轮重试；消费者按 eventId 幂等。
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventMapper outboxMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 单条事件最大自动投递次数，超过即转死信等待人工重放（BE-07）。 */
    @Value("${outbox.max-attempts:10}")
    private int maxAttempts;

    public OutboxPublisher(OutboxEventMapper outboxMapper, RocketMQTemplate rocketMQTemplate) {
        this.outboxMapper = outboxMapper;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.publish-interval-ms:2000}")
    public void publishPending() {
        List<OutboxEventEntity> pending = outboxMapper.selectList(new LambdaQueryWrapper<OutboxEventEntity>()
                .isNull(OutboxEventEntity::getPublishedAt)
                .isNull(OutboxEventEntity::getDeadLetteredAt)
                .and(wrapper -> wrapper.isNull(OutboxEventEntity::getNextAttemptAt)
                        .or().le(OutboxEventEntity::getNextAttemptAt, Instant.now()))
                .orderByAsc(OutboxEventEntity::getCreatedAt)
                .last("limit 100"));
        for (OutboxEventEntity event : pending) {
            try {
                MessageBuilder<String> message = MessageBuilder.withPayload(envelopeJsonOf(event))
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
                recordFailure(event, exception);
            }
        }
    }

    /**
     * 投递失败：累计次数 + 指数退避，超过上限转死信（BE-07）。
     *
     * 为什么必须有上限：原实现失败后只是"留待下一轮"，坏 broker 或坏消息会永远每 2 秒重试——
     * 日志与 CPU 白烧，且运维看不出哪条事件投不出去。转死信后不再自动重试，等待人工重放。
     */
    private void recordFailure(OutboxEventEntity event, Exception exception) {
        int attempts = (event.getAttempts() == null ? 0 : event.getAttempts()) + 1;
        event.setAttempts(attempts);
        if (attempts >= maxAttempts) {
            event.setDeadLetteredAt(Instant.now());
            log.error("Outbox 投递连续失败 {} 次，转入死信待人工重放 eventId={} type={}",
                    attempts, event.getEventId(), event.getEventType(), exception);
        } else {
            long backoffSeconds = Math.min(300L, 1L << Math.min(attempts, 8));
            event.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));
            log.warn("Outbox publish failed eventId={} type={} attempts={} 下次重试 {}s 后",
                    event.getEventId(), event.getEventType(), attempts, backoffSeconds, exception);
        }
        outboxMapper.updateById(event);
    }

    /**
     * 上线消息体 = 完整事件信封（docs/api.md"事件信封"）。
     *
     * 为什么不直接发 {@code event.getPayload()}：消费端要按 eventId 幂等，而裸 payload 不携带 eventId，
     * 幂等在消费侧根本无法实现（judge 的 created 消费者此前没有任何去重，重复投递会重复评测）。
     * 信封把 eventId 一并送到消费端。
     */
    private String envelopeJsonOf(OutboxEventEntity event) throws JsonProcessingException {
        int schemaVersion = event.getSchemaVersion() == null
                ? EventEnvelope.CURRENT_SCHEMA_VERSION
                : event.getSchemaVersion();
        return EventEnvelope.of(event.getEventId(), event.getEventType(), event.getAggregateId(),
                        event.getOccurredAt(), schemaVersion, event.getTraceId(),
                        objectMapper.readTree(event.getPayload()))
                .toJson(objectMapper);
    }
}
