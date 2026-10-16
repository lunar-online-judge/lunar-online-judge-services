-- 资源归属（BACKEND_IMPROVEMENT_PLAN BE-01）：TEACHER 只能管理自己拥有的考试。
-- created_by 保留"谁创建"的事实，owner_teacher_id 表达"谁有权管理"，两者可分离（权限转移是独立审核动作）。
-- 存量数据按 created_by 回填：不给旧数据默认公开；仍无归属的行只有 ADMIN 可见。
alter table contest_schema.contests add column if not exists owner_teacher_id uuid;

update contest_schema.contests
   set owner_teacher_id = created_by
 where owner_teacher_id is null;

create index if not exists idx_contests_owner
    on contest_schema.contests(owner_teacher_id, created_at desc);

comment on column contest_schema.contests.owner_teacher_id is '有权管理该考试的老师用户 ID（BE-01 资源范围）；为空则仅 ADMIN 可见';
