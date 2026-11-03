-- 审计账本已按产品决定下线（audit-service 已从仓库删除）。
-- 该 schema 不再有任何读写方；此脚本用于清理历史环境里的残留。
-- 注意：drop schema cascade 会连同表与数据一起删除，不可回滚。
drop schema if exists audit_schema cascade;
