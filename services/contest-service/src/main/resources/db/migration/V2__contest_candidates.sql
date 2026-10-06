-- 竞赛报名关系（docs/architecture.md §2.1.2）。
-- 移出名单是软删除（status=REMOVED，保留历史提交/成绩/审计关系）；
-- 同一学生重新加入时复用原行置回 ENROLLED，唯一约束 contest_id + student_id 全程生效。
create table if not exists contest_schema.contest_candidates (
    id uuid primary key,
    contest_id uuid not null,
    student_id uuid not null,
    student_no_snapshot varchar(64) not null,
    username_snapshot varchar(100) not null,
    display_name_snapshot varchar(120) not null,
    added_by_teacher_id uuid,
    status varchar(32) not null default 'ENROLLED',
    enrolled_at timestamptz not null default now(),
    removed_at timestamptz,
    constraint uk_contest_candidates unique (contest_id, student_id)
);

create index if not exists idx_contest_candidates_contest
    on contest_schema.contest_candidates(contest_id, status);

comment on table contest_schema.contest_candidates is '竞赛报名关系；学生账号本体归 auth_schema.users，这里只保存关系与学号快照';
comment on column contest_schema.contest_candidates.status is 'ENROLLED/DISABLED/REMOVED';
comment on column contest_schema.contest_candidates.username_snapshot is '报名时的账户名快照（名单查询用，权威值在 auth_schema.users）';
comment on column contest_schema.contest_candidates.display_name_snapshot is '报名时的显示名快照';
