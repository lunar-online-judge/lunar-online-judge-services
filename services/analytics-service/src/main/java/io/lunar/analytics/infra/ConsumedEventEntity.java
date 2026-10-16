package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * analytics_schema.consumed_events 事件消费幂等闸门。
 *
 * 为什么必须有它：RocketMQ 是"至少一次"投递，同一事件在 broker 重试、消费者重启、offset 回退时
 * 会重复到达；事实表虽已按业务键收敛，但闸门在事务内先挡一次，避免重复消息走到无谓的写路径。
 */
@TableName("analytics_schema.consumed_events")
public class ConsumedEventEntity {

    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;

    /** 消费组名：同一事件被多个消费组各自消费时用它区分，闸门因此是"每组各一次"。 */
    private String consumer;

    private Instant processedAt;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getConsumer() { return consumer; }
    public void setConsumer(String consumer) { this.consumer = consumer; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
