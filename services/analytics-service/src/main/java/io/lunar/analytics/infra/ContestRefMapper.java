package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

/**
 * 竞赛参照数据缓存的写入入口。
 *
 * 为什么用 upsert 而不是"先删后插"：contest_refs 是聚合行（一个竞赛一行），
 * upsert 不会让别的读事务在这个竞赛上看到"行消失"的中间态。
 */
@Mapper
public interface ContestRefMapper extends BaseMapper<ContestRefEntity> {

    /**
     * 写入/覆盖一个竞赛的元数据。{@code refreshed_at} 取数据库的 now()：
     * 陈旧判断要与库内时间同源，用应用侧时钟会在多实例时钟漂移时误判。
     */
    @Insert("insert into analytics_schema.contest_refs(contest_id, name, status, start_at, end_at, "
            + "owner_teacher_id, refreshed_at) "
            + "values (#{contestId}, #{name}, #{status}, #{startAt}, #{endAt}, #{ownerTeacherId}, now()) "
            + "on conflict (contest_id) do update set name = excluded.name, status = excluded.status, "
            + "start_at = excluded.start_at, end_at = excluded.end_at, "
            + "owner_teacher_id = excluded.owner_teacher_id, refreshed_at = excluded.refreshed_at")
    int upsert(@Param("contestId") String contestId,
               @Param("name") String name,
               @Param("status") String status,
               @Param("startAt") Instant startAt,
               @Param("endAt") Instant endAt,
               @Param("ownerTeacherId") String ownerTeacherId);

    /**
     * 缓存是否还算新鲜（返回 1 = 新鲜，0 = 缺失或已过期）。
     *
     * 新鲜判定用数据库的 now() 而不是应用侧的 Instant.now()：写入时 refreshed_at 取的就是库内时间，
     * 两边用同一个时钟才不会被多实例之间的时钟漂移判成"明明刚写过却过期"。
     */
    @Select("select count(*)::int from analytics_schema.contest_refs "
            + "where contest_id = #{contestId} "
            + "and refreshed_at >= now() - (#{maxAgeSeconds}::float8 * interval '1 second')")
    int countFresh(@Param("contestId") String contestId, @Param("maxAgeSeconds") long maxAgeSeconds);
}
