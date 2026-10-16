-- 审计账本只追加（BACKEND_IMPROVEMENT_PLAN BE-07："审计记录业务上只追加，DB 角色限制 UPDATE/DELETE"）。
--
-- 为什么用触发器而不是 REVOKE：本仓库所有服务共用同一个数据库角色（也是表 owner），
-- 对 owner 做 REVOKE 不生效；触发器对 owner 同样生效，且随迁移可回滚、可审计。
-- 应用侧本来就没有 UPDATE/DELETE 接口（AuditService 只有 insert 与 select），这里是"就算有人拿到连接也改不了"的兜底。
create or replace function audit_schema.reject_audit_mutation() returns trigger as $$
begin
    raise exception '审计账本只追加：不允许 % audit_logs（id=%）', tg_op, old.id;
end;
$$ language plpgsql;

drop trigger if exists trg_audit_logs_append_only on audit_schema.audit_logs;

create trigger trg_audit_logs_append_only
    before update or delete on audit_schema.audit_logs
    for each row execute function audit_schema.reject_audit_mutation();

comment on function audit_schema.reject_audit_mutation() is '审计账本追加式保护：任何 UPDATE/DELETE 一律拒绝（BE-07）';
