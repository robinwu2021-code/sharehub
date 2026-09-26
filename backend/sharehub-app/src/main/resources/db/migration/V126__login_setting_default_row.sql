-- ════════════════════════════════════════════════════════════════════
-- 登录设置：补默认行 `country='*'`（OPS-SYS-07）
--
-- `sys_login_setting` 自 V5 就存在，四个开关列也都在，**而全仓没有任何一处读它** ——
-- 运营台那个「密码登录 / Apple / Google」开关拨了等于没拨，两端都不认。
-- 本次让它第一次真的生效（`GET /mp/auth/methods` + ConsumerAuthService 的服务端门禁），
-- 而生效的前提是**必须有一行兜底**：端上查不到本国配置时回落 `'*'`，
-- 一行都没有的话 `effective()` 返回 null，门禁要么放行一切（等于没门禁）
-- 要么拒绝一切（把所有人锁在门外）。两种都不可接受，所以默认行是硬前提。
--
-- 取值就是各列的 DDL 默认：OTP 开、其余三个关。
--   · password 关 —— CONSUMER realm 的凭据还没建（见 TDD-C端密码登录与凭据 阶段 2），
--     开着就是让用户撞一个「能点但不通」；
--   · apple/google 关 —— 两个策略都还是 stub 验签。
-- 上线后由运营台按国家逐个拨开，不在迁移里替业务做决定。
--
-- 幂等：`INSERT ... SELECT ... WHERE NOT EXISTS`。不用 `INSERT IGNORE` ——
-- 它会把任何错误（列不存在、字符集不合）也一起吞掉。
-- ════════════════════════════════════════════════════════════════════

INSERT INTO sys_login_setting
    (tenant_id, country, country_name,
     otp_enabled, password_enabled, apple_enabled, google_enabled,
     otp_expire_sec, otp_daily_limit, force_real_name)
SELECT 'MAIN', '*', '默认（未单独配置的国家）',
       1, 0, 0, 0,
       300, 10, 0
WHERE NOT EXISTS (
    SELECT 1 FROM sys_login_setting WHERE tenant_id = 'MAIN' AND country = '*'
);
