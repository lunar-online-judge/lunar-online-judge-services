-- 题目与判题元数据（docs/services.md problem-service 节）。
-- 版本模型：题目本身只有元数据，内容/限制/测试点都在版本上；发布后版本不可修改，改动=创建新版本。
-- 测试点数据 v1 直接落库（小文本）；大文件场景后续迁移对象存储并保存引用。
-- 安全约束：测试点内容绝不能通过面向考生的题目查询返回（docs/services.md）。
create schema if not exists problem_schema;

create table if not exists problem_schema.problems (
    id uuid primary key,
    title varchar(200) not null,
    current_version_id uuid,
    created_by uuid,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

comment on table problem_schema.problems is '题目主表；current_version_id 指向当前对外发布的版本';

create table if not exists problem_schema.problem_versions (
    id uuid primary key,
    problem_id uuid not null,
    version_no int not null,
    title varchar(200) not null,
    statement text not null,
    sample_input text,
    sample_output text,
    time_limit_ms int not null default 2000,
    memory_limit_kb int not null default 262144,
    output_limit_kb int not null default 65536,
    -- 逗号分隔的小写语言代码，如 "c,cpp,java,python"
    allowed_languages varchar(200) not null default 'c,cpp,java,python',
    status varchar(32) not null default 'DRAFT',
    created_by uuid,
    created_at timestamptz not null default now(),
    published_at timestamptz,
    constraint uk_problem_versions_no unique (problem_id, version_no)
);

create index if not exists idx_problem_versions_problem
    on problem_schema.problem_versions(problem_id);

comment on table problem_schema.problem_versions is '题目版本：DRAFT 可编辑（v1 不提供编辑，改动建新版本），PUBLISHED 后不可变；发布时把 problems.current_version_id 指向该版本';
comment on column problem_schema.problem_versions.allowed_languages is '语言白名单，逗号分隔（c/cpp/java/python/go/javascript/typescript）';

create table if not exists problem_schema.testcases (
    id uuid primary key,
    version_id uuid not null,
    name varchar(64) not null,
    input_content text not null,
    expected_output text not null,
    constraint uk_testcases unique (version_id, name)
);

comment on table problem_schema.testcases is '测试点：输入与期望输出；仅判题与管理视图可见，考生 API 不返回';
