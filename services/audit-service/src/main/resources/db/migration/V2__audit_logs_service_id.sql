-- 服务身份单列（BACKEND_IMPROVEMENT_PLAN BE-07："保存 actor/role/服务身份"）。
-- 定时任务 / MQ 消费者用 SERVICE 令牌写入账本时，令牌 sub 是服务名（如 contest-service），
-- 不是用户 uuid，直接塞进 actor_id 会被 PostgreSQL 拒绝（实测：invalid input syntax for type uuid）。
-- 因此服务身份单独存 service_id，用户动作该列为 NULL。
alter table audit_schema.audit_logs add column if not exists service_id varchar(64);

comment on column audit_schema.audit_logs.service_id is '服务身份（SERVICE 令牌的 sub，如 contest-service）；用户发起的动作为 NULL';
comment on column audit_schema.audit_logs.actor_id is '操作者用户 ID；服务身份动作为空（见 service_id），纯系统动作为空且 actor_role=SYSTEM';
