package io.lunar.audit.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lunar.audit.domain.AuditService;
import io.lunar.common.event.EventEnvelope;
import io.lunar.common.event.EventMessages;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 异步审计留痕（BACKEND_IMPROVEMENT_PLAN BE-07）：消费各服务敏感动作写出的 `lunar-audit-recorded`。
 *
 * 与同步写入的两点关键差别：
 * - **actor 是事件里的审计快照**，不再重放用户 Bearer——token 到期不会让补偿永远失败（BE-07 明确要求）；
 * - **幂等靠账本唯一索引** `uk_audit_logs_event_id`：重复投递命中唯一冲突并被吞掉，
 *   "至少一次"投递最终得到"恰好一条"记录。
 *
 * 旧格式（裸 payload，无信封）同样能消费：{@link EventMessages} 会把它整条当 payload，eventId 为 null
 * （此时没有幂等键，退化为业务侧的去重能力）。
 */
@Component
@RocketMQMessageListener(topic = "lunar-audit-recorded", consumerGroup = "audit_recorded_group")
public class AuditRecordedConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(AuditRecordedConsumer.class);

    private final AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditRecordedConsumer(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode messageNode = EventMessages.parse(objectMapper, message);
            String eventId = EventMessages.textOf(messageNode, EventEnvelope.FIELD_EVENT_ID);
            JsonNode payload = EventMessages.payloadOf(objectMapper, message);
            String action = EventMessages.textOf(payload, "action");
            if (action == null) {
                // 没有动作名无法留痕：丢弃并告警（重投也不会变好）
                log.warn("审计事件缺少 action，丢弃：eventId={}", eventId);
                return;
            }
            auditService.record(action,
                    EventMessages.textOf(payload, "actorId"),
                    EventMessages.textOf(payload, "actorRole"),
                    EventMessages.textOf(payload, "serviceId"),
                    EventMessages.textOf(payload, "targetType"),
                    EventMessages.textOf(payload, "targetId"),
                    EventMessages.textOf(payload, "contestId"),
                    EventMessages.textOf(payload, "detail"),
                    EventMessages.textOf(payload, "traceId"),
                    eventId);
            log.info("审计事件已入账：eventId={} action={}", eventId, action);
        } catch (DuplicateKeyException exception) {
            log.info("审计事件重复投递，按幂等跳过");
        } catch (Exception exception) {
            throw new RuntimeException("审计事件处理失败，触发重试", exception);
        }
    }
}
