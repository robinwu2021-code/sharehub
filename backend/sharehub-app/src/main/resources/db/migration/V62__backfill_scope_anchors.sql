-- ============================================================
-- ShareHub · 回填订单/工单的数据范围锚点
--
-- 【这些行为什么是空的】
-- `ord_order` 的三列（location_no / site_no / agent_no）是 V9 加的，但
-- **OrdOrder 实体一直没有这三个字段** —— 实体没有字段，MyBatis-Plus 就写不进去，
-- 于是下单时这三列永远是 NULL。唯一填过它们的是 ScopeAnchorSeeder，
-- 而它 `@ConditionalOnProperty(sharehub.seed.enabled=true)`，
-- 生产 2026-09-23 起是 false（deploy/tencent/README.md 有记录）。
--
-- 工单那边列是写的，但值来自请求体，而 ops-web 的 WorkOrderDraft 里
-- 压根没有这三个字段 —— 从界面开的工单同样是空的。
--
-- 后果：代理在自己后台里看不到这些单。`DataScopeHandler` 注入
-- `WHERE agent_no = ?`，NULL 一行都匹配不上。而页面照常渲染、只是少了行，
-- 不报错也不告警 —— 代理只会觉得「我的单怎么还没到」。
--
-- 写入路径已经在本次一并修好（rent() 与 WoOpsServiceImpl.create() 从机柜反查）。
-- 本迁移只管存量。
--
-- 【回填值是近似，不是事实 —— 说清楚】
-- 锚点的语义是「**下单/开单时的快照**」（见 DataScopeRegistration 里 ord_order 那段），
-- 而历史行没有留下「当时机柜归谁」。这里只能填**现在**的归属：
--   · 期间站点没换过手（绝大多数）→ 与事实一致；
--   · 换过手 → 会把老单算到新东家名下。
-- 仍然选择填，因为另一个选项是留 NULL —— 那意味着**谁都看不见**，
-- 包括本来就该看见的那个代理。宁可少数行归属偏差，不要全部不可见。
--
-- 只动 NULL 行（`<=>` 判空）：已有值的行是当时的快照，改它就是篡改历史。
-- ============================================================
SET NAMES utf8mb4;

UPDATE ord_order o
  JOIN dev_cabinet c ON c.cabinet_no = o.cabinet_no
   SET o.location_no = c.location_no,
       o.site_no     = c.site_no,
       o.agent_no    = c.agent_no
 WHERE o.agent_no IS NULL AND o.site_no IS NULL;

UPDATE wo_order w
  JOIN dev_cabinet c ON c.cabinet_no = w.cabinet_no
   SET w.location_no = c.location_no,
       w.site_no     = c.site_no,
       w.agent_no    = c.agent_no
 WHERE w.agent_no IS NULL AND w.site_no IS NULL;
