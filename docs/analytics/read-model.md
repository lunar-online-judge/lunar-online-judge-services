# analytics-service：分析读模型

> 状态：**已实现**（投影 + 参照数据刷新 + 成绩排名 + 学情 + 监考风险 API）。
> 本文只记录 analytics-service 自己的口径与配置；跨服务的链路见 `docs/architecture.md` 第 5 节，
> 端点与错误码见 `docs/api.md`。

## 1. 这个服务是什么

分析读模型（CQRS 的读侧）：**只消费事件、只写自己的 schema**。业务写路径不经过它，
因此它挂了不影响交卷与判题；它的数据全部可以由 MQ 重放重建。

它解决的是"把多次提交收敛成一个人能读的成绩与风险视图"：

- 提交事实按 `submission_id` 收敛（judged 有两个生产者：judge 直发 + submission 内部回写）；
- 风险事件按 `event_id` 收敛（创建事件建行、审核流转事件就地更新，一条事件一行）。

禁止跨 schema 查库（`docs/services.md`）：需要 contest/problem 的数据时走服务间接口拉取后**缓存成参照数据**。

## 2. schema 与数据流

| 表 | 来源 | 性质 |
|---|---|---|
| `submission_facts` | `lunar-submission-judged` | 事实：一行一次提交的终态 |
| `risk_event_facts` | `lunar-risk-event-created` + `lunar-risk-event-reviewed` | 事实：一行一条风险事件（含审核结论） |
| `ai_finding_facts` | `lunar-ai-finding-created` | 事实：AI 结论（**证据**，不参与成绩） |
| `contest_refs` / `contest_problem_refs` / `contest_candidate_refs` | contest 的 `/internal/contests/{id}/snapshot` | 参照数据缓存（可重建，允许短暂陈旧） |
| `student_contest_scores` | 由 `submission_facts` 聚合 | **物化**读模型（名次是全局序号，无法逐行算） |
| `consumed_events` | 各消费者自己 | 幂等闸门，与事实写入同事务 |

参照数据的两条刷新路径：

1. **按需**：任何带 `contestId` 的请求都会先取一次快照（鉴权本来就需要它），
   发现本地缓存超过 `analytics.refresh.max-age` 就顺带写回——解决"刚建的考试立刻要能查到"；
2. **定时**：每 `analytics.refresh.interval-ms` 刷一次"最近有事实写入"（`analytics.refresh.active-window` 窗口内）
   或"参照数据完全缺失"的考试。**不做全表轮询**：历史考试的参照数据不再变化。

考生名单**保留 `REMOVED`**（快照里怎么给就怎么存）：参照表是忠实副本，过滤是读模型的口径。

## 3. 成绩与排名

- 每题取该生所有提交里的**最高分**，总分 = 各题最高分之和（`docs/exam-process.md` §7 已确认）；
- `solved_count` 统计"存在过 `ACCEPTED` 判决的题目数"，不是"最高分 > 0 的题目数"——
  部分分方案下后者会把只拿到部分分的题也算作已解决；
- 判决事件没带 `problemId` 的提交以 `submission_id` 作为题目键，不会与其他提交混成一道题；
- 名次是 **1..N 的稠密序号、无并列**：排序链末档是唯一的 `student_id`（这也是分页稳定的前提）。

重算的三条路径：

1. **定时增量**（`ScoreboardRecomputeScheduler`）：每 `analytics.recompute.interval-ms` 跑一次，
   只算 `computed_at < judged_at` 的考试——即"读模型落后于事实"，一场没有新判决的考试不会进入候选；
2. **读路径自愈**（`ScoreboardService#ensureComputed`）：读模型为空时顺带算一次（30 秒冷却），
   覆盖"刚部署、历史考试从没算过"；
3. **显式重建**：`POST /api/v1/internal/analytics/rebuild`（同考试进行中返回 409）。

### 3.1 可配口径（产品未确认项）

| 配置 | 默认 | 说明 |
|---|---|---|
| `analytics.ranking.tie-break` | `total_score:desc, last_effective_at:asc, solved_count:desc, student_id:asc` | 同分排序。**前三档待产品确认**（`docs/exam-process.md` §10-1）；最后一档是分页稳定性要求，配置里缺了会被自动补上 |
| `analytics.score-visibility` | `OWN_ONLY` | 成绩对考生的可见范围。**待确认**（§10-6）：只建模 `OWN_ONLY` / `HIDDEN` 两态。"考试结束后放开全部可见"依赖尚未实现的成绩状态机，故意未建模 |
| `analytics.recompute.interval-ms` / `.window` / `.max-contests-per-run` | `600000` / `30m` / `50` | 定时增量重算的周期、候选窗口与单轮上限 |
| `analytics.risk.weight-*` | `high=5, confirmed=10, acknowledged=3, pending=1` | 可疑度权重。**公式在 SQL 里，权重在这里**；口径未确认，全部可调 |
| `analytics.risk.heatmap-bucket-minutes` / `.heatmap-max-buckets` | `30` / `96` | 热力图桶宽与最多返回的格子数（截断方向是"最近优先"） |

配置解析在启动时完成：字段名或方向写错会让服务起不来，而不是让人去猜榜单为什么是这个顺序。

## 4. 学情与监考风险

- **学情不物化**：单个学生的提交量被频率限制封顶，实时聚合足够；物化反而要额外定义失效策略，
  一处通知漏掉就会长期展示过期结论。题目维度以参照题目表为驱动，**无人提交的题目也列出**。
- **风险聚合不物化**：审核状态会被人工反复流转，物化要跟着状态机同步失效。
- **可疑度公式只在 SQL 里定义一处**（`RiskEventFactMapper.selectRiskStudents`），权重来自
  `analytics.risk.*`（`weight-high` / `weight-confirmed` / `weight-acknowledged` / `weight-pending`）。
  排序必须在数据库里做才能配合 offset 分页。
- **AI 结论只作证据**：模型输出可能被提示注入影响，因此不进入可疑度权重、不触发自动处置
  （与 ai-service "可疑度仅用于排序展示"是同一条底线）。

## 5. 已知边界

- **offset 深分页**：榜单/题目/风险列表都是 `limit ... offset ...`。翻到很深的页时
  PostgreSQL 仍要扫描并丢弃前 `offset` 行，代价随页码线性增长。当前规模（一场考试的学生数、题目数）
  可以接受；若要支持"跳到第 1000 页"，应改为按排序键的游标分页（`rank > ?` / `(total_score, student_id) > (?, ?)`）。
- **重算单飞是进程内的**：多实例部署时两个实例仍可能同时重算同一场考试。跨实例互斥需要数据库咨询锁或分布式锁。
- **不需要 offset 的接口**没有分页问题；`scoreboard/me` 与 `problems/{id}/stats` 都是单行/单题。
- **参照数据陈旧窗口**：`contest_refs.*` 在 `refreshed_at` 之前可能过期（例如考试改名）。
  这是"读模型允许最终一致"的既定取舍，不是缺陷。
