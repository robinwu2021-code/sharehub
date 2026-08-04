-- 可归档主数据：统一 archived_at（前端契约 `Archivable`，TDD §10.1「B6 三项横向能力的统一模式」）
--
-- **为什么是时间戳而不是 deleted 布尔**：归档时间本身就是审计信息 ——
-- 布尔位丢掉了「什么时候没的」，出问题时无从回溯。null = 在用，非空 = 已归档。
-- 全平台不做物理删除，契约里禁止出现 delete*（用 archive/unarchive）。
--
-- 注意：与 BaseEntity 的 `deleted`（MyBatis-Plus @TableLogic 软删）**是两回事**：
--   deleted     —— 技术层面的行失效，查询默认不可见，运营端看不到也恢复不了；
--   archived_at —— 业务层面的「停用但保留」，运营端可查看已归档、可恢复。
-- 两者并存是有意的，不要合并。

SET NAMES utf8mb4;

ALTER TABLE agt_agent        ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE loc_site         ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE loc_location     ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE loc_venue        ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE dev_cabinet      ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE dev_powerbank    ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE dev_alarm_code   ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE dev_alarm_rule   ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE mkt_notice       ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE md_bank          ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE md_problem       ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE iam_role         ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE price_plan       ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE coupon_tpl       ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';
ALTER TABLE usr_recharge_pkg ADD COLUMN IF NOT EXISTS archived_at DATETIME(3) NULL COMMENT '归档时间；null=在用';

-- 列表默认过滤已归档，故建索引。放在业务键之后，因为「取某条」比「列已归档」高频得多。
ALTER TABLE agt_agent    ADD KEY IF NOT EXISTS idx_agent_archived (tenant_id, archived_at);
ALTER TABLE loc_site     ADD KEY IF NOT EXISTS idx_site_archived (tenant_id, archived_at);
ALTER TABLE dev_cabinet  ADD KEY IF NOT EXISTS idx_cabinet_archived (tenant_id, archived_at);
