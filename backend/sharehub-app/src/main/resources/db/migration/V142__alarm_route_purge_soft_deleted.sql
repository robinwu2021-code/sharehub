-- ============================================================
-- ShareHub · 清掉 dev_alarm_route 里软删的行（它们占着唯一索引）
--
-- 【症状】同一个告警码**改第二次路由直接 500**：
--   DuplicateKeyException on uk_alarm_route(alarm_code, cause)
--
-- 【原因】saveRoutes 是「整体替换」：先删旧路由再插新的。代码注释写的是
-- 「物理删旧路由（全局配置表，无业务历史意义）」，但 DevAlarmRoute 带 @TableLogic，
-- `routeMapper.delete(wrapper)` 于是是**逻辑删** —— 行还在，deleted=1，
-- 而 uk_alarm_route **不含 deleted 列**。注释说物理删、注解让它逻辑删，两者不一致了很久。
--
-- 这是本仓库栽过的同一个形状：**软删的行仍占着唯一索引**
-- （生产 AG010 撞号那次是编号侧，这次是配置表的整体替换侧）。
--
-- 【怎么改的】RouteMapper 加 @Delete 的 deleteByAlarmCode（真物理删）。
-- 不去给唯一索引加 deleted 列 —— 那等于允许同一个 (码, 根因) 无限堆重复行。
--
-- 本迁移负责存量：把已经躺在表里的软删行清掉，否则受影响的码**改第三次照样 500**。
-- 路由是全局配置，没有业务历史意义，删掉不丢任何可追溯的事实（改动在审计日志里）。
-- ============================================================
SET NAMES utf8mb4;

DELETE FROM dev_alarm_route WHERE deleted = 1;
