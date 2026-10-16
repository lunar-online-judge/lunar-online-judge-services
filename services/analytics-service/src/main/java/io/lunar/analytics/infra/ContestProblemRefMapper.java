package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 竞赛-题目参照数据缓存的写入入口。
 *
 * 为什么这份用"先删后插"而不是 upsert：题目会被移出竞赛，而 upsert 只会补写、不会删除，
 * 被移出的题目会永远留在参照表里，让学情统计凭空多出一道没人做过的题。
 * 删除与插入在同一个事务里（见 ContestRefRefreshService），读侧看不到空窗。
 */
@Mapper
public interface ContestProblemRefMapper extends BaseMapper<ContestProblemRefEntity> {

    @Delete("delete from analytics_schema.contest_problem_refs where contest_id = #{contestId}")
    int deleteByContest(@Param("contestId") String contestId);

    /**
     * 批量写入。{@code refreshed_at} 交给列默认值 now()，与 contest_refs 同源。
     * 调用方必须保证 {@code rows} 非空——空集合会拼出语法非法的 SQL。
     */
    @Insert("""
            <script>
            insert into analytics_schema.contest_problem_refs(contest_id, problem_id, problem_version_id, sort_order)
            values
            <foreach collection="rows" item="row" separator=",">
                (#{row.contestId}, #{row.problemId}, #{row.problemVersionId}, #{row.sortOrder})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<ContestProblemRefEntity> rows);
}
