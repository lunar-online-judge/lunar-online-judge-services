-- Outbox 投递的重试退避与死信（BACKEND_IMPROVEMENT_PLAN BE-07）。
--
-- 原来失败只记一条 warn 然后"留待下一轮"：坏 broker 或坏消息会**永远每 2 秒重试**，
-- 日志与 CPU 白烧、运维也看不到"哪条事件投不出去"。这里补三列：
--   attempts       连续失败次数（成功投递后不再变化）
--   next_attempt_at 下次可尝试时间（指数退避，避免打满）
--   dead_lettered_at 超过上限后转死信，不再自动重试，等待人工重放
alter table submission_schema.outbox_events add column if not exists attempts int not null default 0;
alter table submission_schema.outbox_events add column if not exists next_attempt_at timestamptz;
alter table submission_schema.outbox_events add column if not exists dead_lettered_at timestamptz;

-- 待投递扫描按 (published_at, dead_lettered_at, next_attempt_at, created_at) 过滤，给部分索引减少扫描量
create index if not exists idx_submission_outbox_pending
    on submission_schema.outbox_events(created_at)
    where published_at is null and dead_lettered_at is null;

comment on column submission_schema.outbox_events.attempts is '连续投递失败次数（BE-07）';
comment on column submission_schema.outbox_events.next_attempt_at is '下次可投递时间：指数退避（BE-07）';
comment on column submission_schema.outbox_events.dead_lettered_at is '转入死信的时间：超过上限不再自动重试，等待人工重放（BE-07）';
