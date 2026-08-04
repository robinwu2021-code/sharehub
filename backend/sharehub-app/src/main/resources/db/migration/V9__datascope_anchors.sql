-- 自动生成，勿手改：源文件 docs/technical/ddl/pb_core-v2-datascope.sql
-- 重新同步：python3 backend/scripts/sync-migrations.py
-- 已做的转换：剥离 USE/CREATE DATABASE · 幂等化(IF [NOT] EXISTS) · 剥离 AFTER 子句 ·
--            保留字列名加反引号

-- ============================================================
-- powerbank · B1 数据范围锚点列（[TDD-核心业务逻辑-分模块 §5]）
--
-- 背景：`DataScopeHandler` 只对**已注册锚点表**追加 `agent_no IN (...)` 等条件。
--       注册前提是表上真有那一列 —— 而开发库里 ord_rent / wo_order / dev_cabinet /
--       loc_location **都没有** `agent_no`，导致数据范围至今只能注册 loc_site 一张表，
--       AGENT 角色能看到全量订单与工单（安全缺口，非功能缺口）。
--
-- 本脚本只做两件事：补锚点列 + 按归属链回填。不改任何既有列、不删任何数据。
-- 归属链：loc_site(agent_no) → loc_location → dev_cabinet → ord_rent / wo_order
-- ============================================================
SET NAMES utf8mb4;
-- [sync] 移除切库语句（Flyway 绑定 pb_core）：USE pb_core;

-- ── 1. 补列 ──────────────────────────────────────────────
ALTER TABLE loc_location
  ADD COLUMN IF NOT EXISTS agent_no VARCHAR(36) NULL COMMENT '归属代理(冗余·随站点，数据范围锚点)';

ALTER TABLE dev_cabinet
  ADD COLUMN IF NOT EXISTS site_no  VARCHAR(36) NULL COMMENT '归属站点(冗余·随点位，数据范围锚点)',
  ADD COLUMN IF NOT EXISTS agent_no VARCHAR(36) NULL COMMENT '归属代理(冗余·随站点，数据范围锚点)';

ALTER TABLE ord_rent
  ADD COLUMN IF NOT EXISTS location_no VARCHAR(36) NULL COMMENT '借出点位',
  ADD COLUMN IF NOT EXISTS site_no     VARCHAR(36) NULL COMMENT '借出站点(冗余·数据范围锚点)',
  ADD COLUMN IF NOT EXISTS agent_no    VARCHAR(36) NULL COMMENT '归属代理(冗余·数据范围锚点)';

ALTER TABLE wo_order
  ADD COLUMN IF NOT EXISTS location_no VARCHAR(36) NULL COMMENT '点位',
  ADD COLUMN IF NOT EXISTS site_no     VARCHAR(36) NULL COMMENT '站点(冗余·数据范围锚点)',
  ADD COLUMN IF NOT EXISTS agent_no    VARCHAR(36) NULL COMMENT '归属代理(冗余·数据范围锚点)';

-- ── 2. 按归属链回填（必须自上而下，顺序不可换）──────────────
UPDATE loc_location l
  JOIN loc_site s ON s.site_no = l.site_no
  SET l.agent_no = s.agent_no;

UPDATE dev_cabinet c
  JOIN loc_location l ON l.location_no = c.location_no
  SET c.site_no = l.site_no, c.agent_no = l.agent_no;

UPDATE ord_rent o
  JOIN dev_cabinet c ON c.cabinet_no = o.cabinet_no
  SET o.location_no = c.location_no, o.site_no = c.site_no, o.agent_no = c.agent_no;

UPDATE wo_order w
  JOIN dev_cabinet c ON c.cabinet_no = w.cabinet_no
  SET w.location_no = c.location_no, w.site_no = c.site_no, w.agent_no = c.agent_no;

-- ── 3. 数据范围热路径索引（[db-design §十]）────────────────
-- 数据范围过滤 + 列表默认排序一次走完
ALTER TABLE ord_rent    ADD KEY IF NOT EXISTS idx_ord_scope  (tenant_id, agent_no, status, created_at);
ALTER TABLE wo_order    ADD KEY IF NOT EXISTS idx_wo_scope   (tenant_id, agent_no, status, created_at);
ALTER TABLE dev_cabinet ADD KEY IF NOT EXISTS idx_cab_scope  (tenant_id, agent_no, status);
ALTER TABLE loc_location ADD KEY IF NOT EXISTS idx_loc_scope (tenant_id, agent_no);

-- ============================================================
-- 未覆盖（表尚未创建，建表后需一并注册数据范围）：
--   dev_alarm       → agent_no / site_no
--   share_record    → payee_no (payee_type='AGENT' 时)
--   stl_settlement  → payee_no
-- ============================================================

-- ⚠️ 回填只解决存量。**增量归属靠代理划拨同步** —— `agt_assignment` 目前只落流水，
--    不回写 dev_cabinet.agent_no / loc_location.agent_no / loc_site.agent_no，
--    即划拨后数据范围不生效。见 AgentAssignmentService 的 TODO。
