-- 开发种子管理员（密码 admin123，仅限本地/开发环境）。
-- 由 Flyway 占位符守卫：生产启动必须设置 AUTH_DEV_SEED=false，此迁移即不会插入任何数据。
DO $$
BEGIN
  IF '${dev_seed}' = 'true' THEN
    insert into auth_schema.users (id, username, password_hash, display_name, role, status, password_change_required)
    values ('00000000-0000-0000-0000-000000000001', 'admin',
            '$2a$10$/U7TrZ53foMLuuVPjrBXeuPPcfSHZG2hEOHsRlvTG/LgvOFaNnkS2',
            '平台管理员', 'ADMIN', 'ACTIVE', false)
    on conflict (username) do nothing;
  END IF;
END $$;
