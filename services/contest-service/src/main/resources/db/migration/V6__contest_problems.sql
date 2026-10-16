-- 考试内锁定的题目清单（BACKEND_IMPROVEMENT_PLAN BE-04）。
-- 关键点：把 problem_version_id 固定在考试侧——之后全局再发布新版本，也不会改变正在进行的考试的
-- 题面与测试数据（提交、判题命令、测试点读取都必须用这个固定版本）。
-- 按学生随机分配（docs/exam-process.md §3）是后续能力；本表先表达"这场考试用这些题目的哪个版本"。
create table if not exists contest_schema.contest_problems (
    id uuid primary key,
    contest_id uuid not null,
    problem_id uuid not null,
    problem_version_id uuid not null,
    sort_order int not null default 0,
    created_at timestamptz not null default now(),
    constraint uk_contest_problem unique (contest_id, problem_id)
);

create index if not exists idx_contest_problems_contest
    on contest_schema.contest_problems(contest_id, sort_order);

comment on table contest_schema.contest_problems is '考试锁定的题目与版本快照（BE-04）：全局题目发布不影响已锁定版本';
comment on column contest_schema.contest_problems.problem_version_id is '锁定版本：提交/判题/取测试点都必须用它，而不是题目当前发布版本';
