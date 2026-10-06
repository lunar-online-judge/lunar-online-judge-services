-- 考试公告（docs/exam-process.md §8）。
-- v1 范围：全体公告 + 重要标记 + 需考生确认；"指定题目公告"依赖题目关联，随 problem-service 接入扩展 scope。
-- 送达 = 考生首次拉取公告列表时记录；确认 = 考生显式确认；同一考生同一公告一行（唯一约束）。
create table if not exists contest_schema.announcements (
    id uuid primary key,
    contest_id uuid not null,
    content varchar(4000) not null,
    important boolean not null default false,
    require_confirmation boolean not null default false,
    created_by uuid,
    created_at timestamptz not null default now()
);

create index if not exists idx_announcements_contest
    on contest_schema.announcements(contest_id, created_at desc);

create table if not exists contest_schema.announcement_confirms (
    id uuid primary key,
    announcement_id uuid not null,
    student_id uuid not null,
    delivered_at timestamptz not null default now(),
    confirmed_at timestamptz,
    constraint uk_announcement_confirms unique (announcement_id, student_id)
);

comment on table contest_schema.announcements is '考试公告；公告默认不改变考试时间（docs/exam-process.md §8）';
comment on table contest_schema.announcement_confirms is '公告送达/确认记录：delivered_at=考生首次拉取，confirmed_at=考生显式确认';
