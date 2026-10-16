package io.lunar.ai.infra;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 每日作业配额（ai_schema.ai_quota_counters）。
 *
 * 不继承 BaseMapper：这张表只服务一个动作——"原子占用一个额度"，
 * 而它必须由数据库来保证原子性（先查再写会在并发下超发）。
 */
@Mapper
public interface AiQuotaMapper {

    /**
     * 占用一个当日额度。返回 1 = 占用成功，返回 0 = 当日额度已满。
     *
     * {@code on conflict do update ... where used < 上限} 让"判断 + 累加"落在一条语句里：
     * 并发请求里只有一个能把它推到上限，其余拿到 0 行——这正是配额需要的语义。
     */
    @Insert("""
            insert into ai_schema.ai_quota_counters(day, actor_id, used)
            values (current_date, #{actorId}, 1)
            on conflict (day, actor_id) do update
                set used = ai_schema.ai_quota_counters.used + 1
                where ai_schema.ai_quota_counters.used < #{maxPerDay}
            """)
    int tryConsume(@Param("actorId") String actorId, @Param("maxPerDay") int maxPerDay);
}
