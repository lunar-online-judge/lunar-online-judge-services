package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 事件消费幂等闸门（analytics_schema.consumed_events）。
 *
 * 投影服务不用实体 CRUD，只要一个动作："登记这条 eventId 是否第一次见"；
 * 因此这里只声明那一条语句。实体类仍保留（ConsumedEventEntity），供运维查询按事件追溯消费方。
 */
@Mapper
public interface ConsumedEventMapper extends BaseMapper<ConsumedEventEntity> {

    /**
     * 登记事件已处理。返回 1 = 首次处理（可以继续），返回 0 = 该 eventId 已登记过（重复投递，应跳过）。
     *
     * {@code on conflict do nothing} 让插入同时承担判断职责：并发下只有一个调用方插入成功，
     * 另一个拿到 0 行——这正是"重复投递只处理一次"需要的语义，也是它不能拆成 select + insert 的原因。
     */
    @Insert("insert into analytics_schema.consumed_events(event_id, consumer) "
            + "values (#{eventId}, #{consumer}) on conflict (event_id) do nothing")
    int tryMarkProcessed(@Param("eventId") String eventId, @Param("consumer") String consumer);
}
