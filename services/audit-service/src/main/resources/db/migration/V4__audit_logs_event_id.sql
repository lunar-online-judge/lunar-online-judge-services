-- 审计账本的 eventId 幂等前置（BACKEND_IMPROVEMENT_PLAN BE-07）。
--
-- 目标形态：敏感动作与本地 audit outbox 同事务提交 → 异步 worker 用服务身份投递 →
-- audit 消费端按 eventId 唯一幂等。要做到"唯一"，就必须在账本上留下 eventId 并加唯一约束，
-- 否则重复投递会重复记账（这正是 BE-07 点名的"审计数据库未见 eventId 去重"）。
--
-- 本迁移只做准备：列可空、唯一索引对 NULL 不生效，因此现有的同步写入（没有 eventId）不受影响；
-- 下一轮把写入改成 outbox + 异步消费时，把 eventId 填上即可获得幂等，不需要再改表。
alter table audit_schema.audit_logs add column if not exists event_id uuid;

create unique index if not exists uk_audit_logs_event_id
    on audit_schema.audit_logs(event_id);

comment on column audit_schema.audit_logs.event_id is '来源事件的 eventId（BE-07 异步消费幂等键）；同步写入为 NULL';
