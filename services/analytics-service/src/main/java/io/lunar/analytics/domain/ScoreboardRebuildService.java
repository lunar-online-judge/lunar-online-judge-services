package io.lunar.analytics.domain;

import io.lunar.analytics.domain.dto.RebuildResult;
import io.lunar.analytics.domain.dto.ScoreAggregate;
import io.lunar.analytics.infra.StudentContestScoreEntity;
import io.lunar.analytics.infra.StudentContestScoreMapper;
import io.lunar.analytics.infra.SubmissionFactMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一场考试的成绩重算：从提交事实聚合出每个学生的总分，排好名次，整批替换读模型。
 *
 * 为什么名次在 Java 侧授予而不是 SQL 的 {@code rank() over (...)}：tie-break 是**可配**的
 * （产品口径未确认，见 RankingProperties），把它写成窗口函数意味着每次改口径都要改 SQL 字符串。
 * 聚合的规模是"一场考试的学生数"，排序成本可以忽略。
 *
 * 名次是 1..N 的稠密序号、**没有并列**：排序链的最后一档是唯一的 student_id，
 * 因此不存在两个学生比不出先后。这也是分页稳定的前提（见 RankingField.STUDENT_ID）。
 */
@Service
public class ScoreboardRebuildService {

    private static final Logger log = LoggerFactory.getLogger(ScoreboardRebuildService.class);

    private final SubmissionFactMapper submissionFactMapper;
    private final StudentContestScoreMapper studentContestScoreMapper;
    private final RankingOrder rankingOrder;

    public ScoreboardRebuildService(SubmissionFactMapper submissionFactMapper,
                                    StudentContestScoreMapper studentContestScoreMapper,
                                    RankingOrder rankingOrder) {
        this.submissionFactMapper = submissionFactMapper;
        this.studentContestScoreMapper = studentContestScoreMapper;
        this.rankingOrder = rankingOrder;
    }

    /**
     * 重算并整批替换该考试的成绩行。
     *
     * 删除与写入在同一个事务里：中间态（表里只有一半学生）一旦被读到，
     * 榜单会显示成一个"少了半截"的名次序列，而这比暂时读到旧数据更糟。
     */
    @Transactional
    public RebuildResult rebuild(String contestId) {
        List<ScoreAggregate> aggregates = new ArrayList<>(submissionFactMapper.selectScoreAggregates(contestId));
        aggregates.sort(rankingOrder.comparator());
        Instant computedAt = Instant.now();
        List<StudentContestScoreEntity> rows = new ArrayList<>(aggregates.size());
        for (int index = 0; index < aggregates.size(); index++) {
            rows.add(toEntity(contestId, aggregates.get(index), index + 1, computedAt));
        }
        studentContestScoreMapper.deleteByContest(contestId);
        if (!rows.isEmpty()) {
            studentContestScoreMapper.insertBatch(rows);
        }
        log.info("成绩重算完成：contestId={} students={} tieBreak={}",
                contestId, rows.size(), rankingOrder.effectiveTieBreak());
        return new RebuildResult(contestId, rows.size(), computedAt);
    }

    private static StudentContestScoreEntity toEntity(String contestId, ScoreAggregate aggregate,
                                                      int rank, Instant computedAt) {
        StudentContestScoreEntity row = new StudentContestScoreEntity();
        row.setContestId(contestId);
        row.setStudentId(aggregate.studentId());
        row.setTotalScore(aggregate.totalScore());
        row.setSolvedCount(aggregate.solvedCount());
        row.setLastEffectiveAt(aggregate.lastEffectiveAt());
        row.setRank(rank);
        row.setComputedAt(computedAt);
        return row;
    }
}
