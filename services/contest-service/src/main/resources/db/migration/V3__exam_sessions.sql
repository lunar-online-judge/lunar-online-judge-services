-- 考生会话（docs/exam-process.md §6）：同一学生同一竞赛最多一个会话，重新登录恢复同一会话；
-- 断线（心跳超时）置 EXPIRED，重进恢复；管理端终止置 TERMINATED（v1 决策：终止后不可重进，见 api.md）。
create table if not exists contest_schema.exam_sessions (
    id uuid primary key,
    contest_id uuid not null,
    student_id uuid not null,
    status varchar(32) not null default 'ACTIVE',
    started_at timestamptz not null default now(),
    last_heartbeat_at timestamptz not null default now(),
    closed_at timestamptz,
    constraint uk_exam_sessions unique (contest_id, student_id)
);

create index if not exists idx_exam_sessions_stale
    on contest_schema.exam_sessions(status, last_heartbeat_at);

comment on table contest_schema.exam_sessions is '考生会话；进入条件=名单 ENROLLED + 状态 PUBLISHED/RUNNING + 时间窗口内（服务端时间）';
