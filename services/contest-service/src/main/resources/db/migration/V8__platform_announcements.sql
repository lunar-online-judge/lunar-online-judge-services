-- 平台公告：管理端全局公告，与考试公告（contest_schema.announcements）相互独立。
create table if not exists contest_schema.platform_announcements (
    id uuid primary key,
    title varchar(200) not null,
    content varchar(4000) not null,
    important boolean not null default false,
    created_by uuid,
    created_at timestamptz not null default now()
);

create index if not exists idx_platform_announcements_created
    on contest_schema.platform_announcements(important desc, created_at desc);

comment on table contest_schema.platform_announcements is '平台公告：管理端全局公告，与考试公告相互独立';
