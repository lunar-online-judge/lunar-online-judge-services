alter table auth_schema.users
    add column if not exists student_no varchar(64),
    add column if not exists managed_by_teacher_id uuid,
    add column if not exists password_change_required boolean not null default false;

create unique index if not exists uk_auth_users_student_no
    on auth_schema.users(student_no)
    where student_no is not null;

comment on column auth_schema.users.student_no is '学生学号，学生账号必填；非学生账号为空';
comment on column auth_schema.users.managed_by_teacher_id is '创建或最近一次重置该账号的老师 ID';
comment on column auth_schema.users.password_change_required is '首次登录或密码重置后要求学生修改密码';
