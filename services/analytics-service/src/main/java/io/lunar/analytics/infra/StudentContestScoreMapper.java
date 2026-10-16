package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.lunar.analytics.domain.dto.ScoreboardRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 成绩与排名读模型的读写入口。
 *
 * 写入一律是"按竞赛整批替换"（delete + insertBatch，同一事务内）：
 * 名次是全局序号，任何一个学生的分数变化都可能让后面所有学生的名次平移，
 * 逐行 upsert 会留下一个"新旧名次混在一起"的中间状态被读到。
 */
@Mapper
public interface StudentContestScoreMapper extends BaseMapper<StudentContestScoreEntity> {

    @Delete("delete from analytics_schema.student_contest_scores where contest_id = #{contestId}")
    int deleteByContest(@Param("contestId") String contestId);

    /** 批量写入；调用方必须保证 {@code rows} 非空（空集合会拼出语法非法的 SQL）。 */
    @Insert("""
            <script>
            insert into analytics_schema.student_contest_scores(contest_id, student_id, total_score, solved_count,
                last_effective_at, rank, computed_at)
            values
            <foreach collection="rows" item="row" separator=",">
                (#{row.contestId}, #{row.studentId}, #{row.totalScore}, #{row.solvedCount},
                 #{row.lastEffectiveAt}, #{row.rank}, #{row.computedAt})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<StudentContestScoreEntity> rows);

    /**
     * 榜单分页（{@code studentId} 非空时退化为"取某个学生的那一行"，供考生查自己）。
     *
     * 排序是 {@code rank asc}：名次由重算流程按可配的 tie-break 授予，查询侧不再重新排序，
     * 否则"同一份数据在不同接口里排出的名次不一致"。{@code nulls last} + student_id 兜底是为了
     * 尚未授予名次的行（理论上的中间态）也保持稳定顺序，不让分页漏行或重复行。
     */
    @Select("""
            <script>
            select sc.rank as rank,
                   sc.student_id as student_id,
                   c.student_no as student_no,
                   c.display_name as display_name,
                   sc.total_score as total_score,
                   sc.solved_count as solved_count,
                   sc.last_effective_at as last_effective_at,
                   sc.computed_at as computed_at
            from analytics_schema.student_contest_scores sc
            left join analytics_schema.contest_candidate_refs c
                   on c.contest_id = sc.contest_id and c.student_id = sc.student_id
            where sc.contest_id = #{contestId}
            <if test="studentId != null"> and sc.student_id = #{studentId} </if>
            order by sc.rank asc nulls last, sc.student_id asc
            limit #{limit} offset #{offset}
            </script>
            """)
    List<ScoreboardRow> selectScoreboard(@Param("contestId") String contestId,
                                         @Param("studentId") String studentId,
                                         @Param("limit") long limit,
                                         @Param("offset") long offset);

    /** 榜单总行数（不含参照数据的左连，左边才是权威：缺参照数据的成绩行也应该被计入）。 */
    @Select("select count(*)::int from analytics_schema.student_contest_scores where contest_id = #{contestId}")
    int countByContest(@Param("contestId") String contestId);
}
