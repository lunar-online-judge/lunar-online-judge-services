package io.lunar.analytics.domain;

import io.lunar.analytics.domain.dto.AnalyticsPage;
import io.lunar.analytics.domain.dto.CandidateRef;
import io.lunar.analytics.domain.dto.ScoreAggregate;
import io.lunar.analytics.domain.dto.ScoreboardRow;
import io.lunar.analytics.infra.ScoreboardQueryMapper;
import io.lunar.analytics.infra.SubmissionFactMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成绩榜查询面（简化后：实时聚合，不再读物化表）。
 *
 * 锁定的是**简化过程中最容易改错的三件事**：
 *  1. 名次是**全局序号**——翻到第二页时名次必须接着走，不能从 1 重新开始；
 *  2. 参照数据（学号/姓名）按当前页的学生去取，缺失时该字段为 null 但不影响成绩数据；
 *  3. 空考试返回空页，而不是一行 0 分或异常。
 */
class ScoreboardServiceTest {

    private static final String CONTEST = "11111111-1111-1111-1111-111111111111";

    private final SubmissionFactMapper submissionFactMapper = mock(SubmissionFactMapper.class);
    private final ScoreboardQueryMapper scoreboardQueryMapper = mock(ScoreboardQueryMapper.class);
    private final AnalyticsProperties analyticsProperties = new AnalyticsProperties();
    private ScoreboardService service;

    @BeforeEach
    void setUp() {
        RankingProperties rankingProperties = new RankingProperties();
        rankingProperties.setTieBreak(List.of("total_score:desc", "last_effective_at:asc"));
        service = new ScoreboardService(submissionFactMapper, scoreboardQueryMapper,
                new RankingOrder(rankingProperties), analyticsProperties);
    }

    private static ScoreAggregate aggregate(String studentId, int score, int solved) {
        return new ScoreAggregate(studentId, score, solved, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void rankIsGlobalAcrossPagesNotPageLocal() {
        when(submissionFactMapper.selectScoreAggregates(CONTEST)).thenReturn(List.of(
                aggregate("s_low", 50, 1),
                aggregate("s_high", 200, 2),
                aggregate("s_mid", 100, 1)));
        when(scoreboardQueryMapper.selectCandidateRefs(eq(CONTEST), anyList())).thenReturn(List.of(
                new CandidateRef("s_mid", "S003", "中等"),
                new CandidateRef("s_high", "S001", "高分"),
                new CandidateRef("s_low", "S002", "低分")));

        AnalyticsPage<ScoreboardRow> second = service.scoreboard(CONTEST, 2, 1);

        assertEquals(3, second.total(), "总数是整场考试的人数，不是当前页行数");
        assertEquals(1, second.items().size());
        ScoreboardRow row = second.items().get(0);
        assertEquals(2, row.rank(), "第二页的第一行名次应为 2（全局序号），不能从 1 重来");
        assertEquals("s_mid", row.studentId());
        assertEquals("S003", row.studentNo());
        assertEquals(100, row.totalScore());
    }

    @Test
    void aggregatesAreSortedByTieBreakBeforePaging() {
        when(submissionFactMapper.selectScoreAggregates(CONTEST)).thenReturn(List.of(
                aggregate("s_c", 100, 1),
                aggregate("s_a", 200, 2)));
        when(scoreboardQueryMapper.selectCandidateRefs(eq(CONTEST), anyList())).thenReturn(List.of());

        AnalyticsPage<ScoreboardRow> page = service.scoreboard(CONTEST, 1, 10);

        assertEquals("s_a", page.items().get(0).studentId(), "总分高者在前");
        assertEquals(1, page.items().get(0).rank());
        assertEquals("s_c", page.items().get(1).studentId());
        assertEquals(2, page.items().get(1).rank());
    }

    @Test
    void missingReferenceDataLeavesStudentFieldsNullButKeepsScores() {
        when(submissionFactMapper.selectScoreAggregates(CONTEST)).thenReturn(List.of(aggregate("s_x", 80, 1)));
        when(scoreboardQueryMapper.selectCandidateRefs(eq(CONTEST), anyList())).thenReturn(List.of());

        ScoreboardRow row = service.scoreboard(CONTEST, 1, 10).items().get(0);

        assertNull(row.studentNo(), "参照数据还没刷新到该考生时，学号为 null");
        assertNull(row.displayName());
        assertEquals(80, row.totalScore(), "但成绩数据本身必须照常返回");
    }

    @Test
    void emptyContestReturnsEmptyPage() {
        when(submissionFactMapper.selectScoreAggregates(CONTEST)).thenReturn(List.of());

        AnalyticsPage<ScoreboardRow> page = service.scoreboard(CONTEST, 1, 10);

        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
        // 没有聚合结果时不该再去查参照数据（空 IN 列表会拼出非法 SQL）
        verify(scoreboardQueryMapper, org.mockito.Mockito.never()).selectCandidateRefs(eq(CONTEST), anyList());
    }

    @Test
    void requestedPageBeyondEndIsEmptyButTotalStaysCorrect() {
        when(submissionFactMapper.selectScoreAggregates(CONTEST)).thenReturn(List.of(aggregate("s_a", 100, 1)));
        when(scoreboardQueryMapper.selectCandidateRefs(eq(CONTEST), anyList())).thenReturn(List.of());

        AnalyticsPage<ScoreboardRow> page = service.scoreboard(CONTEST, 5, 10);

        assertEquals(1, page.total());
        assertTrue(page.items().isEmpty(), "翻过尾页返回空列表，不抛异常也不返回错位数据");
    }

    @Test
    void currentRowFindsStudentWithGlobalRank() {
        when(submissionFactMapper.selectScoreAggregates(CONTEST)).thenReturn(List.of(
                aggregate("s_b", 50, 0),
                aggregate("s_a", 100, 1)));
        when(scoreboardQueryMapper.selectCandidateRefs(eq(CONTEST), anyList())).thenReturn(List.of(
                new CandidateRef("s_b", "S002", "乙")));

        ScoreboardRow row = service.currentRow(CONTEST, "s_b");

        assertEquals(2, row.rank(), "学情里的名次必须与榜单一致");
        assertEquals("S002", row.studentNo());
        assertNull(service.currentRow(CONTEST, "s_missing"), "从没提交过的学生没有成绩行");
    }
}
