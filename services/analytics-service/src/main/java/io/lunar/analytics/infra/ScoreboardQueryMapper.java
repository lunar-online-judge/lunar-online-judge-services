package io.lunar.analytics.infra;

import io.lunar.analytics.domain.dto.CandidateRef;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 榜单的参照数据查询（学号/姓名）。
 *
 * 为什么单独成 Mapper：成绩本身来自 `submission_facts` 的实时聚合，参照数据在另一张表，
 * 两者职责不同。原来它们由物化表的一条 join 一起取出，物化层去掉后拆成"先聚合、再按需取参照"——
 * 只取当前页这几个学生的参照，比每页 join 整场考试更省。
 */
@Mapper
public interface ScoreboardQueryMapper {

    @Select("""
            <script>
            select student_id as student_id,
                   student_no as student_no,
                   display_name as display_name
            from analytics_schema.contest_candidate_refs
            where contest_id = #{contestId}
              and student_id in
              <foreach collection="studentIds" item="studentId" open="(" separator="," close=")">
                  #{studentId}::uuid
              </foreach>
            </script>
            """)
    List<CandidateRef> selectCandidateRefs(@Param("contestId") String contestId,
                                           @Param("studentIds") List<String> studentIds);
}
