-- Outbox 投递的重试退避与死信（BACKEND_IMPROVEMENT_PLAN BE-07；与 submission 的 V3 同一模式）。
--
-- 原实现失败只记一条 warn 然后"留待下一轮"：坏 broker 或坏消息会永远每 2 秒重试，
-- 日志与 CPU 白烧、运维也看不到"哪条事件投不出去"。
alter table anti_cheat_schema.outbox_events add column if not exists attempts int not null default 0;
alter table anti_cheat_schema.outbox_events add column if not exists next_attempt_at timestamptz;
alter table anti_cheat_schema.outbox_events add column if not exists dead_lettered_at timestamptz;

create index if not exists idx_anti_cheat_outbox_pending
    on anti_cheat_schema.outbox_events(created_at)
    where published_at is null and dead_lettered_at is null;

comment on column anti_cheat_schema.outbox_events.attempts is '连续投递失败次数（BE-07）';
comment on column anti_cheat_schema.outbox_events.next_attempt_at is '下次可投递时间：指数退避（BE-07）';
comment on column anti_cheat_schema.outbox_events.dead_lettered_at is '转入死信的时间：超过上限不再自动重试，等待人工重放（BE-07）';
