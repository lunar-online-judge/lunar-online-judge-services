-- 简化：AI findings 读模型下线。
--
-- 背景：原设计里 ai-service 把每条 finding 通过 Outbox → RocketMQ 回写到 analytics，
-- analytics 维护 ai_finding_facts 读模型供查询。按简化决定，这条跨服务链路整体去掉：
-- findings 只在 ai-service 内查询，analytics 不再保留该读模型。
--
-- 为什么新增迁移而不是改历史迁移：已应用过的迁移不能改；而且"删表"是不可逆动作，
-- 必须留在迁移历史里可追溯。开发期该表无生产数据。
drop table if exists analytics_schema.ai_finding_facts;
