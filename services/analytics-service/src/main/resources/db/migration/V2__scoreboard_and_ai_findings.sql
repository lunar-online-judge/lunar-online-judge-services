-- 增量 2：成绩与排名的物化表、AI 结论事实表。
--
-- 为什么排名要物化：排名是**全局序号**（第几名），而序号无法由"读一行算一行"的方式得出——
-- 每次查询都要把整场考试的提交重新聚合、重新排序。物化之后查询退化成按 rank 取一页，
-- 重算则交给定时任务与显式重建端点。

create table if not exists analytics_schema.student_contest_scores (
    contest_id uuid not null,
    student_id uuid not null,
    total_score int not null default 0,
    solved_count int not null default 0,
    -- 该生最后一次有效判决时间：同分时"更早达到该分数"者优先（docs/exam-process.md §10-1 待确认）
    last_effective_at timestamptz,
    rank int,
    computed_at timestamptz not null default now(),
    primary key (contest_id, student_id)
);

-- 榜单查询路径：按竞赛取分页，按总分降序、同分者先达到者在前
create index if not exists idx_student_contest_scores_ranking
    on analytics_schema.student_contest_scores(contest_id, total_score desc, last_effective_at asc);

comment on table analytics_schema.student_contest_scores is '成绩与排名读模型：每题取该生最高分，总分=各题最高分之和；由定时任务或显式重建端点重算';
comment on column analytics_schema.student_contest_scores.rank is '竞赛内名次（1 起，按 analytics.ranking.tie-break 排序后授予，无并列：最后一档是 student_id）';
comment on column analytics_schema.student_contest_scores.last_effective_at is '该生最后一次判决时间，供同分排序；仅作排序依据，不代表成绩已终审（成绩状态机未实现）';

-- AI 结论事实：消费 lunar-ai-finding-created 投影而来。
--
-- 为什么不直接调 ai-service 的 /api/v1/ai/findings：那个端点是 TEACHER/ADMIN 语义（需要用户令牌），
-- 而分析侧的合并发生在"监考风险"查询里、由服务侧组装；复制成事实表也与其它读模型一致（可重放）。
create table if not exists analytics_schema.ai_finding_facts (
    finding_id uuid primary key,
    job_id uuid,
    finding_type varchar(64),
    contest_id uuid not null,
    problem_id uuid,
    submission_id uuid,
    student_id uuid,
    severity varchar(32),
    -- 可疑度：**仅用于排序展示，不得据此自动处置**（与 ai-service 的 AiFindingView 同一约定）
    score int,
    summary varchar(1000),
    model varchar(128),
    prompt_version varchar(64),
    created_at timestamptz not null,
    projected_at timestamptz not null default now()
);

-- 查询路径一：按竞赛+学生取证据（风险学生行合并 AI 结论）
create index if not exists idx_ai_finding_facts_contest_student
    on analytics_schema.ai_finding_facts(contest_id, student_id, created_at desc);

-- 查询路径二：按竞赛统计结论分布（summary 里的 finding 计数）
create index if not exists idx_ai_finding_facts_contest_severity
    on analytics_schema.ai_finding_facts(contest_id, severity, score desc);

comment on table analytics_schema.ai_finding_facts is 'AI 结论事实（证据）：消费 lunar-ai-finding-created 投影，仅作风险研判的佐证，不参与成绩计算';
comment on column analytics_schema.ai_finding_facts.score is '模型给出的可疑度（0-100）；仅用于排序展示，禁止据此自动处置';
