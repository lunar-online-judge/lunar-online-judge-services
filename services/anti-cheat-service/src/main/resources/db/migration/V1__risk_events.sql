-- 风险事件与人工审核（docs/exam-process.md §6、docs/services.md anti-cheat 节）。
-- 原则：单一事件不能自动判定作弊；severity 由服务端规则表计算（客户端上报值不采信）；
-- 审核状态机 OPEN -> ACKNOWLEDGED -> CONFIRMED / DISMISSED；确认/驳回留痕（审核人/时间/备注）。
create schema if not exists anti_cheat_schema;

create table if not exists anti_cheat_schema.risk_events (
    id uuid primary key,
    contest_id uuid not null,
    student_id uuid not null,
    problem_id uuid,
    event_type varchar(64) not null,
    severity varchar(32) not null,
    detail varchar(2000),
    occurred_at timestamptz not null,
    reported_at timestamptz not null default now(),
    review_status varchar(32) not null default 'OPEN',
    reviewed_by uuid,
    reviewed_at timestamptz,
    review_note varchar(500)
);

create index if not exists idx_risk_events_query
    on anti_cheat_schema.risk_events(contest_id, reported_at desc);

create index if not exists idx_risk_events_review
    on anti_cheat_schema.risk_events(review_status, reported_at desc);

comment on table anti_cheat_schema.risk_events is '风险事件：客户端/监考端上报，severity 由服务端规则表计算（不采信上报值）';
comment on column anti_cheat_schema.risk_events.review_status is '人工审核状态机 OPEN/ACKNOWLEDGED/CONFIRMED/DISMISSED';

-- Outbox：风险事件创建 → lunar-risk-event-created（gateway WebSocket 告警的未来消费方）
create table if not exists anti_cheat_schema.outbox_events (
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
