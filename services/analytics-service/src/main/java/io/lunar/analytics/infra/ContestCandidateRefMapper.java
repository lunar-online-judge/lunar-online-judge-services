package io.lunar.analytics.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 竞赛-考生参照数据缓存的写入入口。
 *
 * 与题目参照表同样用"先删后插"：名单会变（移出名单、重新导入），
 * upsert 无法表达"这个人已经不在名单里了"。
 *
 * 注意 {@code enrollment_status} 原样保留（含 REMOVED）——缓存层不做过滤，
 * 过滤是读模型的口径问题（docs/exam-process.md §9：移出名单不影响历史提交与评测结果）。
 */
@Mapper
public interface ContestCandidateRefMapper extends BaseMapper<ContestCandidateRefEntity> {

    @Delete("delete from analytics_schema.contest_candidate_refs where contest_id = #{contestId}")
    int deleteByContest(@Param("contestId") String contestId);

    /** 批量写入；调用方必须保证 {@code rows} 非空（空集合会拼出语法非法的 SQL）。 */
    @Insert("""
            <script>
            insert into analytics_schema.contest_candidate_refs(contest_id, student_id, student_no, username,
                display_name, enrollment_status)
            values
            <foreach collection="rows" item="row" separator=",">
                (#{row.contestId}, #{row.studentId}, #{row.studentNo}, #{row.username},
                 #{row.displayName}, #{row.enrollmentStatus})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<ContestCandidateRefEntity> rows);
}
