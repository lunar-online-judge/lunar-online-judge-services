-- 会话失效时间线（后续后端优化计划 BE-09 第二部分）。
--
-- 背景：JWT 是离线校验的，禁用账号或重置密码**不会**让已签发的令牌失效——旧令牌在到期前（默认 30 分钟）照样能用。
-- 这里记录"该用户此时间点之前签发的令牌一律无效"：重置密码等敏感操作把它置为当前时刻，
-- 资源服务拿着令牌的 iat 与之比较即可拒绝旧令牌。传播时限 = 资源服务本地缓存的 TTL（默认 30 秒）。
alter table auth_schema.users add column if not exists tokens_valid_after timestamptz;

comment on column auth_schema.users.tokens_valid_after is '此时间之前签发的令牌一律无效（BE-09）；NULL 表示从未失效过';
