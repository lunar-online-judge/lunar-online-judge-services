package io.lunar.analytics.infra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成绩聚合口径的集成测试（analytics-service 此前零测试）。
 *
 * **直接执行 {@link ScoreAggregateSql#PER_STUDENT_TOTALS} 本身**，而不是在测试里另抄一份 SQL——
 * 抄一份就只能证明"抄的那份对"，实现改了测试照样绿。这条 SQL 一旦算错，成绩榜会**静默出错**，
 * 比崩溃更难发现，所以必须钉死。
 *
 * 锁定的四条口径：
 *  1. 总分 = **每题最高分之和**（同一题交多次取最高，不是累加）；
 *  2. 解题数 = **存在 ACCEPTED 的题数**，不是"最高分 > 0"的题数（部分分不算解出）；
 *  3. 最后有效时间 = 该生所有判题时间的最大值；
 *  4. `problem_id` 为空时以 `submission_id` 为键——否则所有缺题目归属的提交会被并成"同一题"。
 *
 * 需要真实 PostgreSQL（与 application.yml 相同默认值：localhost:5433/lunar，用户 lunar）。
 * 未启动数据库时本测试失败，这是**预期**：它测的就是 SQL 在真实 PG 上的行为。
 */
class ScoreAggregateSqlTest {

    private static final String URL = System.getenv().getOrDefault("POSTGRES_HOST", "localhost");
    private static final String PORT = System.getenv().getOrDefault("POSTGRES_PORT", "5433");
    private static final String DB = System.getenv().getOrDefault("POSTGRES_DB", "lunar");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_USER", "lunar");
    private static final String PASSWORD = System.getenv().getOrDefault("POSTGRES_PASSWORD", "change-me");

    private final UUID contestId = UUID.randomUUID();
    private Connection connection;

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                "jdbc:postgresql://" + URL + ":" + PORT + "/" + DB, USER, PASSWORD);
    }

    @BeforeEach
    void openConnection() throws Exception {
        connection = connect();
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from analytics_schema.submission_facts where contest_id = ?")) {
            statement.setObject(1, contestId);
            statement.executeUpdate();
        } finally {
            connection.close();
        }
    }

    private void insertFact(UUID studentId, UUID problemId, Integer score, String verdict, String judgedAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into analytics_schema.submission_facts
                    (submission_id, contest_id, problem_id, student_id, verdict, score, judged_at)
                values (?, ?, ?, ?, ?, ?, ?::timestamptz)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, contestId);
            statement.setObject(3, problemId);
            statement.setObject(4, studentId);
            statement.setString(5, verdict);
            if (score == null) {
                statement.setNull(6, java.sql.Types.INTEGER);
            } else {
                statement.setInt(6, score);
            }
            statement.setString(7, judgedAt);
            statement.executeUpdate();
        }
    }

    /** 执行被测 SQL 本体，返回 studentId -> [totalScore, solvedCount, lastEffectiveAt]。 */
    private Map<UUID, Object[]> aggregates() throws Exception {
        String sql = ScoreAggregateSql.PER_STUDENT_TOTALS.replace("#{contestId}", "?");
        Map<UUID, Object[]> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, contestId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.put((UUID) rows.getObject("student_id"),
                            new Object[]{rows.getInt("total_score"), rows.getInt("solved_count"),
                                    rows.getTimestamp("last_effective_at")});
                }
            }
        }
        return result;
    }

    @Test
    void totalScoreIsSumOfPerProblemBestNotSumOfAllSubmissions() throws Exception {
        UUID student = UUID.randomUUID();
        UUID problemA = UUID.randomUUID();
        UUID problemB = UUID.randomUUID();
        // 同一题交三次：60 / 100 / 40 → 只取最高的 100
        insertFact(student, problemA, 60, "PARTIAL", "2026-01-01T10:00:00Z");
        insertFact(student, problemA, 100, "ACCEPTED", "2026-01-01T10:05:00Z");
        insertFact(student, problemA, 40, "PARTIAL", "2026-01-01T10:10:00Z");
        // 另一题只有部分分
        insertFact(student, problemB, 50, "PARTIAL", "2026-01-01T10:20:00Z");

        Object[] row = aggregates().get(student);

        assertEquals(150, row[0], "总分应为各题最高分之和：100 + 50");
    }

    @Test
    void solvedCountCountsAcceptedProblemsNotPositiveScores() throws Exception {
        UUID student = UUID.randomUUID();
        UUID solved = UUID.randomUUID();
        UUID partialOnly = UUID.randomUUID();
        insertFact(student, solved, 100, "ACCEPTED", "2026-01-01T10:00:00Z");
        // 拿到分但没有 ACCEPTED：不算解出
        insertFact(student, partialOnly, 80, "PARTIAL", "2026-01-01T10:05:00Z");

        Object[] row = aggregates().get(student);

        assertEquals(1, row[1], "解题数只数存在 ACCEPTED 的题，部分分不算解出");
        assertEquals(180, row[0], "但部分分仍计入总分");
    }

    @Test
    void lastEffectiveAtIsMaxJudgedAtAcrossProblems() throws Exception {
        UUID student = UUID.randomUUID();
        insertFact(student, UUID.randomUUID(), 100, "ACCEPTED", "2026-01-01T09:00:00Z");
        insertFact(student, UUID.randomUUID(), 50, "PARTIAL", "2026-01-01T11:30:00Z");

        Object[] row = aggregates().get(student);

        assertEquals(java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T11:30:00Z")), row[2]);
    }

    @Test
    void submissionsWithoutProblemIdAreKeyedBySubmissionSoTheyDoNotMerge() throws Exception {
        UUID student = UUID.randomUUID();
        // 两次都没有 problem_id：必须各自成"题"，不能并成一道题取最高
        insertFact(student, null, 30, "PARTIAL", "2026-01-01T10:00:00Z");
        insertFact(student, null, 40, "PARTIAL", "2026-01-01T10:10:00Z");

        Object[] row = aggregates().get(student);

        assertEquals(70, row[0], "缺 problem_id 时以 submission_id 为键，两次提交应累加为 30 + 40");
    }

    @Test
    void nullScoreCountsAsZeroAndStillProducesARow() throws Exception {
        UUID student = UUID.randomUUID();
        insertFact(student, UUID.randomUUID(), null, "COMPILE_ERROR", "2026-01-01T10:00:00Z");

        Object[] row = aggregates().get(student);

        assertEquals(0, row[0], "score 为 null 视为 0，不能因为 coalesce 缺失让整行消失");
        assertEquals(0, row[1]);
    }

    @Test
    void otherContestsAreExcluded() throws Exception {
        UUID student = UUID.randomUUID();
        insertFact(student, UUID.randomUUID(), 100, "ACCEPTED", "2026-01-01T10:00:00Z");
        // 另一场考试的同一学生，不应出现在本场聚合里
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into analytics_schema.submission_facts
                    (submission_id, contest_id, problem_id, student_id, verdict, score, judged_at)
                values (?, ?, ?, ?, 'ACCEPTED', 999, now())
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, UUID.randomUUID());
            statement.setObject(3, UUID.randomUUID());
            statement.setObject(4, student);
            statement.executeUpdate();
        }

        Object[] row = aggregates().get(student);

        assertEquals(100, row[0], "只统计本场考试（contest_id 过滤）");
    }

    @Test
    void emptyContestProducesNoRows() throws Exception {
        assertTrue(aggregates().isEmpty(), "无提交的考试应返回空集，而不是一行 0 分");
        assertNull(aggregates().get(UUID.randomUUID()));
    }
}
