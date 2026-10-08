-- 审计账本（docs/architecture.md §14、docs/services.md audit-service 节）。
-- 追加式：只有插入，没有更新/删除接口；普通业务不得删除审计记录（本服务不提供删除能力）。
-- action 为自由字符串（大写下划线约定，如 STUDENT_CREATED/RISK_EVENT_REVIEWED），不硬编码校验，
-- 避免新动作类型因注册遗漏而丢账；使用中的动作清单见 docs/services.md。
create schema if not exists audit_schema;

create table if not exists audit_schema.audit_logs (
    id uuid primary key,
    action varchar(64) not null,
    actor_id uuid,
    actor_role varchar(32),
    target_type varchar(64),
    target_id uuid,
    contest_id uuid,
    detail text,
    trace_id varchar(64),
    occurred_at timestamptz not null default now(),
    created_at timestamptz not null default now()
);

create index if not exists idx_audit_logs_query
    on audit_schema.audit_logs(occurred_at desc);

create index if not exists idx_audit_logs_contest
    on audit_schema.audit_logs(contest_id, occurred_at desc);

create index if not exists idx_audit_logs_actor
    on audit_schema.audit_logs(actor_id, occurred_at desc);

create index if not exists idx_audit_logs_action
    on audit_schema.audit_logs(action, occurred_at desc);

comment on table audit_schema.audit_logs is '审计账本（追加式）：管理员操作、权限变更、账号管理、审核动作、媒体访问等留痕；不记录密码及任何敏感明文';
comment on column audit_schema.audit_logs.actor_id is '操作者用户 ID；系统动作可空（actor_role=SYSTEM）';
