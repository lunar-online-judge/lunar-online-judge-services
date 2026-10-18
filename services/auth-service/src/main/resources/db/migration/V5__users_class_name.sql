-- 学生班级：仅 CANDIDATE 使用；其他角色为 NULL。
alter table auth_schema.users add column if not exists class_name varchar(100);

create index if not exists idx_users_class_name on auth_schema.users(class_name);

comment on column auth_schema.users.class_name is '学生班级名称；仅 CANDIDATE 使用';
