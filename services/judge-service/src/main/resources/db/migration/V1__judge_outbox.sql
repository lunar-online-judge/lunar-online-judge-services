-- judge 的判决 Outbox（后续后端优化计划 ②）。
--
-- 此前 judge 评测完直接 syncSend lunar-submission-judged：broker 抖动/重启/网络分区时这一发就丢了，
-- 结果是考生成绩永久缺失、提交永远停在 QUEUED（没有任何地方能补投）。judge 原本没有数据库，
-- 这里只引入 judge_schema 下这一张表：判决先落本地，再由发布器异步投递；失败按指数退避重试，
-- 超过上限转死信等待人工重放（与 submission/anti-cheat 的 Outbox 同一模式）。
create table if not exists judge_schema.outbox_events (
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

create unique index if not exists uk_judge_outbox_event_id on judge_schema.outbox_events(event_id);

create index if not exists idx_judge_outbox_pending
    on judge_schema.outbox_events(created_at)
    where published_at is null and dead_lettered_at is null;

comment on table judge_schema.outbox_events is '判决事件发件箱：判决先落本地再异步投递，broker 不可用也不丢判决';
comment on column judge_schema.outbox_events.dead_lettered_at is '转入死信的时间：超过投递上限不再自动重试，等待人工重放';
