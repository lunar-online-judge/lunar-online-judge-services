-- 审计记录的"动作实际发生时间"（BACKEND_IMPROVEMENT_PLAN BE-07：originalOccurredAt）。
--
-- 为什么要它：审计改为 outbox + 异步投递后，账本里的 created_at 是**入账时间**，
-- 而投递可能因 audit-service 重启/积压而晚几分钟甚至更久。排查"什么时候发生的"必须看动作发生时间，
-- 所以把事件信封里的 occurredAt 一并落库。同步写入没有事件时间，留 NULL。
alter table audit_schema.audit_logs add column if not exists original_occurred_at timestamptz;

comment on column audit_schema.audit_logs.original_occurred_at is '动作实际发生时间（事件信封 occurredAt）；同步写入为 NULL，入账时间见 created_at';
