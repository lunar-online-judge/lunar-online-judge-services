package io.lunar.judge.mq;

import io.lunar.common.event.EventEnvelope;
import io.lunar.judge.infra.OutboxEventEntity;
import io.lunar.judge.infra.OutboxEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 判决 Outbox 发布器（后续后端优化计划 ②）。
 *
 * 判决先由消费者落进 {@code judge_schema.outbox_events}，这里再异步投递到 RocketMQ：
 * broker 抖动/重启只影响投递时机，不再丢判决。失败按指数退避重试，超过上限转死信等待人工重放。
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** 单轮发布的最大事件数，避免一次拉取过多拖垮调度线程。 */
    private static final int PUBLISH_BATCH_SIZE = 100;

    private final OutboxEventMapper outboxMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 单条事件最大自动投递次数，超过即转死信等待人工重放。 */
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
                .last("limit " + PUBLISH_BATCH_SIZE));
        for (OutboxEventEntity event : pending) {
            try {
                MessageBuilder<String> message = MessageBuilder.withPayload(envelopeJsonOf(event))
                        .setHeader("KEYS", event.getEventId());
                // traceId 为空时不能设该头：RocketMQ 拒绝空属性值，设了整条消息就发不出去
                if (event.getTraceId() != null && !event.getTraceId().isBlank()) {
                    message.setHeader("traceId", event.getTraceId());
                }
                rocketMQTemplate.syncSend(event.getEventType(), message.build());
                event.setPublishedAt(Instant.now());
                outboxMapper.updateById(event);
                log.info("判决事件已投递 eventId={} type={}", event.getEventId(), event.getEventType());
            } catch (Exception exception) {
                recordFailure(event, exception);
            }
        }
    }

    /** 投递失败：累计次数 + 指数退避，超过上限转死信。 */
    private void recordFailure(OutboxEventEntity event, Exception exception) {
        int attempts = (event.getAttempts() == null ? 0 : event.getAttempts()) + 1;
        event.setAttempts(attempts);
        if (attempts >= maxAttempts) {
            event.setDeadLetteredAt(Instant.now());
            log.error("判决事件投递连续失败 {} 次，转入死信待人工重放 eventId={}",
                    attempts, event.getEventId(), exception);
        } else {
            long backoffSeconds = Math.min(300L, 1L << Math.min(attempts, 8));
            event.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));
            log.warn("判决事件投递失败 eventId={} attempts={} 下次重试 {}s 后",
                    event.getEventId(), attempts, backoffSeconds, exception);
        }
        outboxMapper.updateById(event);
    }

    /** 上线消息体 = 完整事件信封（docs/api.md"事件信封"）：消费端按 eventId 幂等。 */
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
