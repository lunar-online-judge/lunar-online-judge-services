package io.lunar.submission.infra;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 事件消费幂等闸门（submission_schema.consumed_events，见 docs/api.md"事件信封"）。
 *
 * 不继承 BaseMapper：这张表只服务一个动作——"登记这条 eventId 是否第一次见"，
 * 没有按实体增删改查的需求，多一个实体类只会多一处需要维护的映射。
 */
@Mapper
public interface ConsumedEventMapper {

    /**
     * 登记事件已处理。返回 1 = 首次处理（可以继续），返回 0 = 该 eventId 已登记过（重复投递，应跳过）。
     *
     * {@code on conflict do nothing} 让插入同时承担判断职责：并发下只有一个调用方插入成功，
     * 另一个拿到 0 行——这正是"重复投递只处理一次"需要的语义。
     */
    @Insert("insert into submission_schema.consumed_events(event_id, consumer) "
            + "values (#{eventId}, #{consumer}) on conflict (event_id) do nothing")
    int tryMarkProcessed(@Param("eventId") String eventId, @Param("consumer") String consumer);
}
