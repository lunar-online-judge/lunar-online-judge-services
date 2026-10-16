-- ai_schema：AI 能力的作业、结论、草稿与 Outbox（docs/services.md 的 ai-service 节）。
--
-- 设计红线（贯穿全表）：
--   1) AI **产证据，不改事实**——本 schema 不写成绩、不写审核状态、不写题目正文；
--      出题结果只落 problem_drafts 草稿，教师确认后走 problem-service 既有创建接口。
--   2) 作业异步化：LLM 调用是慢且贵的操作，绝不能占着 HTTP 请求线程；
--      ai_jobs 就是队列，领取用 `for update skip locked`。
--   3) 成果投递与 submission/judge/anti-cheat 同一模式：Outbox + 指数退避 + 死信。
create schema if not exists ai_schema;

-- 作业队列。status: PENDING | RUNNING | SUCCEEDED | FAILED
create table if not exists ai_schema.ai_jobs (
    id uuid primary key,
    job_type varchar(32) not null,
    status varchar(16) not null default 'PENDING',
    contest_id uuid,
    problem_id uuid,
    submission_id uuid,
    student_id uuid,
    requested_by uuid not null,
    -- 发起时角色快照：资源范围判断与账本都按"当时的角色"解释，不随账号后来改角色而变
    requested_role varchar(32) not null,
    -- 输入指纹：同一份输入（同提交、同题面、同 prompt 版本）已有成功结果时直接复用，不重复烧配额
    input_hash varchar(64) not null,
    -- 作业类型专属输入（如出题需求描述）；通用引用（contest/problem/submission/student）另有专列
    input_payload jsonb,
    -- 入队时的全链路追踪 ID：worker 执行时请求上下文已结束，靠它把 AI 动作接回原始请求
    trace_id varchar(128),
    prompt_version varchar(32) not null,
    provider varchar(64),
    model varchar(128),
    -- 失败原因与错误码（AI_PROVIDER_UNAVAILABLE / AI_INVALID_MODEL_OUTPUT 等）
    status_detail varchar(500),
    error_code varchar(64),
    attempts int not null default 0,
    next_attempt_at timestamptz,
    -- 租约：领取后超过该时间仍为 RUNNING，视为执行方已死，可被重新领取
    lease_until timestamptz,
    tokens_prompt int,
    tokens_completion int,
    created_at timestamptz not null default now(),
    started_at timestamptz,
    finished_at timestamptz
);

-- 待领取扫描的部分索引：队列热路径只扫"可跑"的行
create index if not exists idx_ai_jobs_runnable
    on ai_schema.ai_jobs(created_at)
    where status = 'PENDING';
create index if not exists idx_ai_jobs_lease
    on ai_schema.ai_jobs(lease_until)
    where status = 'RUNNING';
-- 列表按考试过滤（教师范围查询）+ 稳定排序
create index if not exists idx_ai_jobs_scope on ai_schema.ai_jobs(contest_id, created_at desc);

comment on table ai_schema.ai_jobs is 'AI 作业队列：异步执行，领取用 for update skip locked；LLM 调用不占用 HTTP 请求线程';
comment on column ai_schema.ai_jobs.requested_role is '发起时角色快照：范围判断与账本按当时角色解释';
comment on column ai_schema.ai_jobs.input_hash is '输入指纹：同输入已有成功结果时复用，不重复消耗 Provider 配额';

-- 结论：AI 的全部产出都落这里，供人工查看与后续消费方读取
create table if not exists ai_schema.ai_findings (
    id uuid primary key,
    job_id uuid not null references ai_schema.ai_jobs(id),
    finding_type varchar(32) not null,
    contest_id uuid,
    problem_id uuid,
    submission_id uuid,
    student_id uuid,
    -- severity/score 只用于排序与展示；**任何自动处置都不允许读它做决策**（仓库铁律：单一事件/单一信号不能自动判作弊）
    severity varchar(16),
    score int,
    summary varchar(1000) not null,
    detail jsonb not null,
    model varchar(128),
    prompt_version varchar(32) not null,
    created_at timestamptz not null default now()
);

create index if not exists idx_ai_findings_submission on ai_schema.ai_findings(submission_id);
create index if not exists idx_ai_findings_scope on ai_schema.ai_findings(contest_id, created_at desc);
create index if not exists idx_ai_findings_student on ai_schema.ai_findings(student_id, created_at desc);

comment on table ai_schema.ai_findings is 'AI 结论（证据）：只读产出，不驱动自动改分或自动处罚';
comment on column ai_schema.ai_findings.score is '可疑度 0..100：仅用于排序展示，禁止据此自动处置';

-- 相似度对：语义查重的产物。两条通道分别落列，便于解释"为什么判定相似"
create table if not exists ai_schema.similarity_pairs (
    id uuid primary key,
    contest_id uuid not null,
    problem_id uuid not null,
    left_submission_id uuid not null,
    right_submission_id uuid not null,
    -- MinHash(Jaccard 估计)：不依赖任何外部服务，是默认通道
    minhash_similarity numeric(5,4),
    -- embedding 余弦：需要 Provider 支持 embedding 模型，未启用时为空
    embedding_similarity numeric(5,4),
    run_id uuid not null,
    created_at timestamptz not null default now(),
    constraint uk_ai_similarity_pair unique (left_submission_id, right_submission_id, run_id)
);

create index if not exists idx_ai_similarity_scope
    on ai_schema.similarity_pairs(contest_id, problem_id, created_at desc);

comment on table ai_schema.similarity_pairs is '相似提交对：minhash 与 embedding 两条通道分列，便于解释判定依据';

-- 出题草稿：**只暂存**，绝不写 problem_schema——教师确认后走 problem 既有创建接口，
-- 这样"发布后版本不可变""发布前至少 1 个测试点"等既有不变量都不需要为 AI 让路
create table if not exists ai_schema.problem_drafts (
    id uuid primary key,
    job_id uuid not null references ai_schema.ai_jobs(id),
    -- 归属考试：可为空（不绑定考试的出题），但给了就用于教师的资源范围判断（BE-01）
    contest_id uuid,
    title varchar(200) not null,
    statement text not null,
    sample_input text,
    sample_output text,
    suggested_testcases jsonb,
    knowledge_tags jsonb,
    difficulty varchar(16),
    language_hint varchar(32),
    review_status varchar(16) not null default 'DRAFT',
    reviewed_by uuid,
    reviewed_at timestamptz,
    created_at timestamptz not null default now()
);

-- 一个作业最多产出一份草稿：作业重试（租约过期被重新领取）时靠它避免重复草稿
create unique index if not exists uk_ai_problem_drafts_job on ai_schema.problem_drafts(job_id);

create index if not exists idx_ai_problem_drafts_scope
    on ai_schema.problem_drafts(contest_id, created_at desc);

comment on table ai_schema.problem_drafts is 'AI 出题草稿：需教师确认后才由 problem-service 落库（本表绝不写 problem_schema）';
comment on column ai_schema.problem_drafts.review_status is 'DRAFT/ACCEPTED/REJECTED：由教师确认，AI 自己不能改';

-- 教师对结论的有用性反馈：AI 质量闭环的唯一输入来源，不做自动化调参
create table if not exists ai_schema.finding_feedback (
    id uuid primary key,
    finding_id uuid not null references ai_schema.ai_findings(id),
    actor_id uuid not null,
    verdict varchar(16) not null,
    note varchar(500),
    created_at timestamptz not null default now(),
    constraint uk_ai_finding_feedback unique (finding_id, actor_id)
);

comment on table ai_schema.finding_feedback is '结论有用性反馈（USEFUL/NOT_USEFUL）：人工质量闭环';

-- 配额计数：按（天，调用者）原子占用，防止一次误操作把 Provider 配额烧穿
create table if not exists ai_schema.ai_quota_counters (
    day date not null,
    actor_id uuid not null,
    used int not null default 0,
    primary key (day, actor_id)
);

comment on table ai_schema.ai_quota_counters is '每日作业配额计数：insert ... on conflict do update ... where used < 上限 实现原子占用';

-- Outbox：与 submission / anti-cheat / judge 完全同一模式（含指数退避与死信）
create table if not exists ai_schema.outbox_events (
    id uuid primary key,
    event_id uuid not null,
    event_type varchar(128) not null,
    aggregate_id varchar(128) not null,
    schema_version int not null default 1,
    trace_id varchar(128),
    payload jsonb not null,
    occurred_at timestamptz not null,
    created_at timestamptz not null default now(),
    published_at timestamptz,
    attempts int not null default 0,
    next_attempt_at timestamptz,
    dead_lettered_at timestamptz
);

create unique index if not exists uk_ai_outbox_event_id on ai_schema.outbox_events(event_id);
create index if not exists idx_ai_outbox_pending
    on ai_schema.outbox_events(created_at)
    where published_at is null and dead_lettered_at is null;

comment on table ai_schema.outbox_events is 'AI 成果事件发件箱（lunar-ai-finding-created / lunar-audit-recorded）：先落本地再异步投递';
comment on column ai_schema.outbox_events.dead_lettered_at is '转入死信的时间：超过投递上限不再自动重试，等待人工重放';
