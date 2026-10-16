-- 事件消费幂等闸门（docs/api.md"事件信封"：消费者必须按 eventId 幂等）。
--
-- 为什么不复用 submissions.status 的终态保护：终态保护只能拦住"提交已在终态"这一种重复，
-- 而同一 eventId 在事务提交前重放时读到的是同一个非终态，两路都"看起来合法"，状态会被写两遍、
-- judged 事件会发两条。闸门把"判断重复"和"插入"合成一条语句，从根上消掉 select-then-insert 的竞态。
--
-- 与业务事实在同一个本地事务里插入：事务回滚时闸门一起回滚，
-- 因此不会出现"登记了已处理、但判决没落库"的黑洞（那种情况下消息已被 ack，判决就永久丢了）。
create table if not exists submission_schema.consumed_events (
    event_id uuid primary key,
    consumer varchar(64) not null,
    processed_at timestamptz not null default now()
);

comment on table submission_schema.consumed_events is '事件消费幂等闸门：eventId 主键，与业务事实同事务插入（docs/api.md 事件信封）';
comment on column submission_schema.consumed_events.consumer is '消费组名：同一个 eventId 被多个消费者各自消费时用于区分';
