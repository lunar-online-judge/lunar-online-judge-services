-- 资源归属（BACKEND_IMPROVEMENT_PLAN BE-01）：TEACHER 只能管理自己拥有的题目。
-- created_by 保留"谁创建"的事实，owner_teacher_id 表达"谁有权管理"；存量按 created_by 回填，
-- 回填不到的行只有 ADMIN 可见（不给旧数据默认公开）。
alter table problem_schema.problems add column if not exists owner_teacher_id uuid;

update problem_schema.problems
   set owner_teacher_id = created_by
 where owner_teacher_id is null;

create index if not exists idx_problems_owner
    on problem_schema.problems(owner_teacher_id, created_at desc);

comment on column problem_schema.problems.owner_teacher_id is '有权管理该题目的老师用户 ID（BE-01 资源范围）；为空则仅 ADMIN 可见';
