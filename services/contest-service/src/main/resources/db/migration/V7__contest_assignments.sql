-- 随机题目分配（docs/exam-process.md §3）：发布时按学生生成分配结果，并固定题目版本。
-- 保存 contestId / studentId / problemVersionId / randomSeed / assignedAt / 分配版本，
-- 保证考试后可以复现"这位学生当时看到的是哪些题、哪个版本"。
alter table contest_schema.contests add column if not exists problems_per_student int;

comment on column contest_schema.contests.problems_per_student is '每位学生随机抽取的题目数量；NULL 或 0 表示不随机（使用全部锁定题目）';

create table if not exists contest_schema.contest_assignments (
    id uuid primary key,
    contest_id uuid not null,
    student_id uuid not null,
    problem_id uuid not null,
    problem_version_id uuid not null,
    random_seed bigint not null,
    assignment_version int not null default 1,
    assigned_at timestamptz not null default now(),
    constraint uk_contest_assignment unique (contest_id, student_id, problem_id)
);

create index if not exists idx_contest_assignments_student
    on contest_schema.contest_assignments(contest_id, student_id);

comment on table contest_schema.contest_assignments is '按学生的随机题目分配：randomSeed 保证可复现，problemVersionId 固定题面与测试数据';
comment on column contest_schema.contest_assignments.random_seed is '该学生本次分配的随机种子：同一 (考试, 学生) 恒定，重算得到同一组题';
