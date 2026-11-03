-- 成绩改为实时聚合（见 ScoreboardService）：物化表不再有读写方，删除以免留下"看起来是事实源"的假象。
-- 保留 submission_facts：它仍是跨服务判题数据的唯一合法入口（不能跨 schema 查库）。
drop table if exists analytics_schema.student_contest_scores;
