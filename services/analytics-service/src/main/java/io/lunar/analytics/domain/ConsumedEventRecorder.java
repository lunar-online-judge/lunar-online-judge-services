package io.lunar.analytics.domain;

import io.lunar.analytics.infra.ConsumedEventMapper;
import org.springframework.stereotype.Service;

/**
 * 事件消费幂等闸门。
 *
 * 为什么单独成类而不写在消费者里：闸门登记必须与事实写入处在同一个事务里，
 * 而事务边界在消费者方法上（{@code @Transactional}）；把 insert 语句留在这里，
 * 消费者只表达"这条事件是否第一次见"的业务含义，不必关心它在数据库里怎么实现。
 *
 * 为什么不做 Redis 预筛（judge-service 那种）：分析侧的事实本来就落在自己的库里，
 * 闸门跟着本地事务一起提交或回滚才是正确性所需；多一层缓存只会引入"缓存说处理过、库里没有"的分歧。
 */
@Service
public class ConsumedEventRecorder {

    private final ConsumedEventMapper consumedEventMapper;

    public ConsumedEventRecorder(ConsumedEventMapper consumedEventMapper) {
        this.consumedEventMapper = consumedEventMapper;
    }

    /**
     * 登记事件并回答"是否首次处理"。
     *
     * {@code eventId} 为空（旧格式裸 payload 消息没有信封）时不做闸门，返回 true——
     * 此时没有可用的幂等键，只能依赖事实表自身的收敛语义，这与 submission-service 的取舍一致。
     *
     * @param eventId  信封事件 ID，可为 null
     * @param consumer 消费组名，用于区分同一事件的不同消费方
     * @return true = 首次处理（继续投影）；false = 重复投递（调用方应跳过）
     */
    public boolean markProcessed(String eventId, String consumer) {
        if (eventId == null || eventId.isBlank()) {
            return true;
        }
        return consumedEventMapper.tryMarkProcessed(eventId, consumer) == 1;
    }
}
