-- ============================================================
-- powerbank · 补齐「实体有字段、迁移没建列」的最后三张表
--
-- 背景：这是 2026-09-23 反复撞到的同一类缺陷（V35 / V39 各修过一批）。
-- 本机库里这些列是历史上手工加的，所以本地永远正常；干净库（生产）一 SELECT/INSERT
-- 就报 Unknown column，表现是**整页 500 或应用直接起不来**
-- —— 这次灌演示数据时 `loc_venue.location_count` 让服务没起来。
--
-- 这次做了全量比对（129 张表的实体字段 vs 生产库实际列），剩余问题只有三处，
-- 一次补完，不再撞一个修一个。
--
-- 口径沿用 V39 定下的那条：**名字可以冗余，计数不可以**。
--   · 名字（assignee_name / location_name）变化少、变了也不影响钱 → 补列；
--   · 计数（location_count / cabinet_count）是关系的聚合，存成列必然与实际脱节 →
--     不补列，改查询时现算（db-design §1.4）。
-- ============================================================
SET NAMES utf8mb4;

-- ── 工单：两个展示用的冗余名 + 一个展示用时间 ──
-- wo_created_at 与审计列 created_at 是两回事：前者是**工单单据上的建单时间**
-- （可由外部系统带入、可人工补录），后者是这一行数据什么时候写进库的。
ALTER TABLE wo_order
  ADD COLUMN IF NOT EXISTS location_name  VARCHAR(128) NULL COMMENT '点位名（冗余，仅展示）',
  ADD COLUMN IF NOT EXISTS assignee_name  VARCHAR(64)  NULL COMMENT '处理人名（冗余，仅展示）',
  ADD COLUMN IF NOT EXISTS wo_created_at  VARCHAR(32)  NULL COMMENT '单据建单时间(UTC ISO)，与审计列 created_at 不同';

-- ── 代理商分成率 ──
-- 它不是聚合值，是一个真实的业务配置（代理商档案上的默认分成率），只是漏了建列。
-- ⚠️ 真正参与分账的仍是 `share_rule`（见 ShareGeneratorImpl）——
-- 这一列是档案上的展示/默认值，不要拿它去算钱。
ALTER TABLE agt_agent
  ADD COLUMN IF NOT EXISTS share_rate DECIMAL(5,4) NULL COMMENT '档案上的默认分成率 0..1；分账以 share_rule 为准';

-- location_count / cabinet_count 刻意**不补列**，见上面的口径说明。

-- ── OTA 发布的乐观锁列 ──
-- `dev_ota_release` 的 `version` 列被固件版本占了，实体便把乐观锁挪到 `version_col`
-- （`@Version @TableField("version_col")`），但**从来没有迁移建过这一列**。
-- 干净库上后果不是查询报错，而是**任何一次更新都失败**：
-- MyBatis-Plus 的乐观锁会把它写进 UPDATE 的 SET 与 WHERE 里。
-- 本机库里它是手工加的，所以本地一直正常。
ALTER TABLE dev_ota_release
  ADD COLUMN IF NOT EXISTS version_col BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁（version 列已被固件版本占用）';
