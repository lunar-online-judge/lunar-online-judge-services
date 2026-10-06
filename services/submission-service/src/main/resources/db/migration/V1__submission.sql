-- 提交与成绩读模型（docs/architecture.md §8）。v1 范围：提交落库 + 幂等 + Outbox 事件行；
-- 事件发布（Outbox publisher → lunar-submission-created）待 RocketMQ 接入。
-- 已提交任务不因客户端关闭而删除（结算策略见 docs/exam-process.md §10 待确认项）。
create schema if not exists submission_schema;

create table if not exists submission_schema.submissions (
    id uuid primary key,
    contest_id uuid not null,
    problem_id uuid not null,
    problem_version_id uuid,
    student_id uuid not null,
    language varchar(32) not null,
    source_code text not null,
    source_hash varchar(64) not null,
    time_limit_ms int,
    memory_limit_kb int,
    status varchar(32) not null default 'QUEUED',
    score int,
    passed_cases int,
    total_cases int,
    idempotency_key varchar(128),
    request_fingerprint varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_submissions_idempotency unique (student_id, idempotency_key)
);

create index if not exists idx_submissions_student_contest
    on submission_schema.submissions(student_id, contest_id, created_at desc);

comment on table submission_schema.submissions is '提交与成绩读模型；status 终态含 ACCEPTED/WRONG_ANSWER/COMPILE_ERROR/TIME_LIMIT_EXCEEDED/MEMORY_LIMIT_EXCEEDED/RUNTIME_ERROR/OUTPUT_LIMIT_EXCEEDED/SYSTEM_ERROR';
comment on column submission_schema.submissions.idempotency_key is 'Idempotency-Key：同学生内唯一；同键同指纹返回首次结果，同键不同指纹 409';
comment on column submission_schema.submissions.request_fingerprint is 'problemId+language+sourceHash 指纹，用于幂等键冲突判定';

create table if not exists submission_schema.outbox_events (
    id uuid primary key,
    event_id uuid not null unique,
    event_type varchar(64) not null,
    aggregate_id uuid not null,
    schema_version int not null default 1,
    trace_id varchar(64),
    payload jsonb not null,
    occurred_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    published_at timestamptz
);

create index if not exists idx_outbox_unpublished
    on submission_schema.outbox_events(created_at)
    where published_at is null;

comment on table submission_schema.outbox_events is 'Outbox：与业务事实同事务落库，发布器（后续）投递 RocketMQ 后置 published_at；事件信封见 docs/api.md"事件信封"';
