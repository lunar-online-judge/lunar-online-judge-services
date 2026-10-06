-- 考试/竞赛主表（考试与竞赛为同一业务概念，见 docs/exam-process.md §1）。
-- 生命周期 DRAFT -> REVIEWING -> PUBLISHED -> RUNNING -> FINISHED -> ARCHIVED 由应用层状态机约束。
-- 报名名单（contest_candidates）、考生会话、随机分配表随对应功能落地，不在此迁移中。
create schema if not exists contest_schema;

create table if not exists contest_schema.contests (
    id uuid primary key,
    name varchar(200) not null,
    description varchar(2000),
    status varchar(32) not null default 'DRAFT',
    start_at timestamptz,
    end_at timestamptz,
    created_by uuid,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_contest_time_window check (start_at is null or end_at is null or start_at < end_at)
);

comment on table contest_schema.contests is '考试/竞赛主表；发布前需完成时间窗口、名单、题目等配置（docs/exam-process.md §1）';
comment on column contest_schema.contests.status is 'DRAFT/REVIEWING/PUBLISHED/RUNNING/FINISHED/ARCHIVED';
comment on column contest_schema.contests.created_by is '创建老师/管理员的用户 ID（auth_schema.users.id）';
