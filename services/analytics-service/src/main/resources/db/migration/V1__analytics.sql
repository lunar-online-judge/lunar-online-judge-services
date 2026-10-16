-- analytics-service 分析读模型（docs/architecture.md 第 5 节：lunar-submission-judged / lunar-risk-event-created /
-- lunar-risk-event-reviewed 的消费方）。本 schema 只放"投影结果"，不代表任何业务服务的权威数据：
-- 业务写入仍归属各自服务的 schema，这里的事实表可以由 MQ 重放重建。

create schema if not exists analytics_schema;

-- 提交事实：一行一次提交的终态。
--
-- 主键为什么不是 event_id：lunar-submission-judged 有两个生产者（judge 直发、submission 内部回写端点），
-- 同一提交会带着两个不同的 eventId 各来一次；按 submission_id 收敛，两路才能落到同一行并最终一致，
-- event_id 只作为"最后一次改写者"的追溯信息保留。
create table if not exists analytics_schema.submission_facts (
    submission_id uuid primary key,
    event_id uuid,
    contest_id uuid not null,
    problem_id uuid,
    problem_version_id uuid,
    student_id uuid not null,
    language varchar(32),
    verdict varchar(32),
    score int,
    passed_cases int,
    total_cases int,
    submitted_at timestamptz,
    judged_at timestamptz not null
);

-- 查询路径一：竞赛内按学生看提交时间线（成绩面板/个人作答记录）。
create index if not exists idx_submission_facts_contest_student_judged
    on analytics_schema.submission_facts(contest_id, student_id, judged_at desc);

-- 查询路径二：竞赛内按题目统计通过率（题目难度与通过分布）。
create index if not exists idx_submission_facts_contest_problem_verdict
    on analytics_schema.submission_facts(contest_id, problem_id, verdict);

comment on table analytics_schema.submission_facts is '提交事实（终态）：按 submission_id 收敛判决事件，contest/problem/student 维度供聚合查询';
comment on column analytics_schema.submission_facts.event_id is '最后一次改写本行的 judged 事件 ID，仅作追溯；不承担唯一性';
comment on column analytics_schema.submission_facts.judged_at is '判题结论确立时间：优先取事件信封的 occurredAt（消费侧收到消息时已无法知道引擎何时出的结果）';

-- 风险事件事实：一条风险事件一行，审核流转就地修正。
--
-- 主键为什么是 event_id：风险事件是不可变事实，一条只产生一个 eventId；
-- lunar-risk-event-reviewed 携带的是同一个 riskEventId（即 eventId），因此审核结论落在同一行，
-- 不会把"上报"与"审核"拆成两行。
create table if not exists analytics_schema.risk_event_facts (
    event_id uuid primary key,
    contest_id uuid not null,
    student_id uuid not null,
    problem_id uuid,
    event_type varchar(64) not null,
    severity varchar(32),
    occurred_at timestamptz,
    review_status varchar(32) not null default 'OPEN',
    reviewed_at timestamptz,
    reviewed_by uuid
);

-- 查询路径一：竞赛内按发生时间看风险事件流（监考时间线）。
create index if not exists idx_risk_event_facts_contest_occurred
    on analytics_schema.risk_event_facts(contest_id, occurred_at desc);

-- 查询路径二：按审核状态筛选待处置队列（OPEN/ACKNOWLEDGED 的积压量）。
create index if not exists idx_risk_event_facts_contest_review
    on analytics_schema.risk_event_facts(contest_id, review_status);

comment on table analytics_schema.risk_event_facts is '风险事件事实：创建事件建行、审核流转事件就地更新，一条事件一行';
comment on column analytics_schema.risk_event_facts.review_status is '人工审核状态：OPEN/ACKNOWLEDGED/CONFIRMED/DISMISSED（与 anti-cheat 状态机同名同义）';

-- 参照数据缓存：下一轮由定时任务从 contest / problem 的服务间接口拉取填充。
--
-- 为什么在分析库里复制一份：事实表只存 ID，聚合结果要展示竞赛名、题目顺序与考生学号；
-- 缓存下来查询就不必跨服务调用，也不会被上游抖动拖垮（代价是 refreshed_at 之前的数据可能陈旧）。
create table if not exists analytics_schema.contest_refs (
    contest_id uuid primary key,
    name varchar(200),
    status varchar(32),
    start_at timestamptz,
    end_at timestamptz,
    owner_teacher_id uuid,
    refreshed_at timestamptz not null default now()
);

comment on table analytics_schema.contest_refs is '竞赛参照数据缓存：由定时任务刷新，分析查询不回源 contest-service';

create table if not exists analytics_schema.contest_problem_refs (
    contest_id uuid,
    problem_id uuid,
    problem_version_id uuid,
    sort_order int,
    refreshed_at timestamptz not null default now(),
    primary key (contest_id, problem_id)
);

comment on table analytics_schema.contest_problem_refs is '竞赛-题目参照数据缓存：problem_version_id 记录拉取时的版本，sort_order 供题目列表排序';

create table if not exists analytics_schema.contest_candidate_refs (
    contest_id uuid,
    student_id uuid,
    student_no varchar(64),
    username varchar(100),
    display_name varchar(120),
    enrollment_status varchar(32),
    refreshed_at timestamptz not null default now(),
    primary key (contest_id, student_id)
);

comment on table analytics_schema.contest_candidate_refs is '竞赛-考生参照数据缓存：榜单与监考视图据此展示学号/姓名，避免按 studentId 逐个回源 auth-service';

-- 消费幂等闸门：与 submission_schema.consumed_events 同构。
--
-- 为什么事实表已经按业务键收敛还需要它：RocketMQ 是"至少一次"投递，重复消息会在事务里再走一遍
-- 解析与写路径；闸门把重复挡在写之前，也让"这条事件被哪个消费组处理过"可查。
-- 与事实写入必须在同一个事务里：否则会出现"登记了已处理、事实没落库"的黑洞，
-- 而消息已被 ack，事实就永久丢了。
create table if not exists analytics_schema.consumed_events (
    event_id uuid primary key,
    consumer varchar(64) not null,
    processed_at timestamptz not null default now()
);

comment on table analytics_schema.consumed_events is '事件消费幂等闸门：eventId 主键，与事实写入同事务插入';
comment on column analytics_schema.consumed_events.consumer is '消费组名：同一个 eventId 被多个消费组各自消费时用于区分';
