package io.lunar.analytics.domain;

import io.lunar.analytics.domain.dto.ScoreAggregate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 榜单排序规则的契约——analytics-service 此前**零测试**，这是第一块安全网。
 *
 * 锁定三件容易改坏的事：
 *  1. 默认按总分降序；
 *  2. **空值恒排最后，与排序方向无关**（"从未判决"的学生不该因为升序跑到最前）；
 *  3. 同分时用 studentId 兜底，保证全序——这是分页稳定的前提（否则同档两人顺序由排序算法决定）。
 */
class RankingOrderTest {

    private static RankingOrder order(String... tieBreak) {
        RankingProperties properties = new RankingProperties();
        properties.setTieBreak(List.of(tieBreak));
        return new RankingOrder(properties);
    }

    private static List<String> ids(List<ScoreAggregate> aggregates) {
        return aggregates.stream().map(ScoreAggregate::studentId).toList();
    }

    @Test
    void sortsByTotalScoreDescending() {
        List<ScoreAggregate> rows = new ArrayList<>(List.of(
                new ScoreAggregate("s_low", 50, 1, Instant.parse("2026-01-01T00:00:00Z")),
                new ScoreAggregate("s_high", 200, 2, Instant.parse("2026-01-01T00:00:00Z"))));

        rows.sort(order("total_score:desc").comparator());

        assertEquals(List.of("s_high", "s_low"), ids(rows));
    }

    @Test
    void nullLastEffectiveAtAlwaysSortsLastRegardlessOfDirection() {
        List<ScoreAggregate> rows = new ArrayList<>(List.of(
                new ScoreAggregate("s_none", 100, 1, null),
                new ScoreAggregate("s_early", 100, 1, Instant.parse("2026-01-01T00:00:00Z")),
                new ScoreAggregate("s_late", 100, 1, Instant.parse("2026-01-02T00:00:00Z"))));

        // 升序：更早达成同分者在前；"从未判决"的仍排最后
        rows.sort(order("total_score:desc", "last_effective_at:asc").comparator());
        assertEquals(List.of("s_early", "s_late", "s_none"), ids(rows));

        // 降序：方向反转，"从未判决"依然在最后
        rows.sort(order("total_score:desc", "last_effective_at:desc").comparator());
        assertEquals(List.of("s_late", "s_early", "s_none"), ids(rows));
    }

    @Test
    void tiesAreBrokenByStudentIdSoPaginationIsStable() {
        List<ScoreAggregate> rows = new ArrayList<>(List.of(
                new ScoreAggregate("s_c", 100, 1, Instant.parse("2026-01-01T00:00:00Z")),
                new ScoreAggregate("s_a", 100, 1, Instant.parse("2026-01-01T00:00:00Z")),
                new ScoreAggregate("s_b", 100, 1, Instant.parse("2026-01-01T00:00:00Z"))));

        rows.sort(order("total_score:desc").comparator());

        // 全序：同分同时间的学生按 studentId 定序，不会随排序算法抖动
        assertEquals(List.of("s_a", "s_b", "s_c"), ids(rows));
    }
}
