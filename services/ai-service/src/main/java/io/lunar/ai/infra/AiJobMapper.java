package io.lunar.ai.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiJobMapper extends BaseMapper<AiJobEntity> {

    /**
     * 领取下一个可执行作业（在调用方的事务里执行）。
     *
     * {@code for update skip locked} 是这里的关键：多个实例/线程同时来领时各领各的行，
     * 不会有两个 worker 跑同一个作业——重复的 LLM 调用既浪费配额，也会产出两份内容不同的 finding。
     *
     * 两类行可领：① 待执行的 PENDING（且退避时间已到）；② 租约过期的 RUNNING（执行方已死）。
     */
    @Select("""
            select * from ai_schema.ai_jobs
            where (status = 'PENDING' and (next_attempt_at is null or next_attempt_at <= now()))
               or (status = 'RUNNING' and lease_until < now())
            order by created_at
            for update skip locked
            limit 1
            """)
    AiJobEntity lockNextRunnable();
}
