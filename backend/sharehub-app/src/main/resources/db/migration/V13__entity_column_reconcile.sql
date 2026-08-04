-- 补齐实体已声明、但表里缺失的列（38 张表 / 113 列）。
--
-- 来源：`backend/scripts/entity-column-diff.py` 按 @TableName **逐类**扫描实体，
--       与 information_schema 实际列比对得出 —— **不是人工列的清单**。
--       （旧脚本按文件取首个 @TableName，IamEntities.java 一文件五实体 → 误报"iam_role 缺 15 列"，已修正。）
--
-- 类型来源分级（每列注释标明，便于复核）：
--   1. 既有 DDL 同名列（跨表复用已定义类型，最可信；可空性/默认值已剥离）
--   2. db-design 显式标注（JSON / DECIMAL 精度）
--   3. db-design §1.5 命名约定（金额 DECIMAL(18,2)、比率 DECIMAL(5,4)、业务键 VARCHAR(36)…）
--   4. 人工定型（前三者查不到，依据写在该列注释里，共 13 列）
--
-- 全部 ADD COLUMN IF NOT EXISTS 且**一律可空**：存量行已有数据，NOT NULL 无默认值会插入失败。
-- 业务必填由 service 层保证；本迁移只解决「列不存在导致 SQL 报错」。

SET NAMES utf8mb4;


-- acct_account（1 列）
ALTER TABLE acct_account
  ADD COLUMN IF NOT EXISTS `frozen` DECIMAL(18,2) NULL COMMENT '约定:金额';

-- acct_ledger（3 列）
ALTER TABLE acct_ledger
  ADD COLUMN IF NOT EXISTS `account_no` VARCHAR(36) NULL COMMENT '既有DDL同名列×2',
  ADD COLUMN IF NOT EXISTS `biz_no` VARCHAR(36) NULL COMMENT '既有DDL同名列×1',
  ADD COLUMN IF NOT EXISTS `biz_type` VARCHAR(24) NULL COMMENT '既有DDL同名列×1';

-- ad_campaign（5 列）
ALTER TABLE ad_campaign
  ADD COLUMN IF NOT EXISTS `ad_no` VARCHAR(36) NULL COMMENT '约定:业务键',
  ADD COLUMN IF NOT EXISTS `advertiser` JSON NULL COMMENT 'db-design 标 JSON',
  ADD COLUMN IF NOT EXISTS `creative` JSON NULL COMMENT 'db-design 标 JSON',
  ADD COLUMN IF NOT EXISTS `currency` VARCHAR(8) NULL COMMENT '既有DDL同名列×31',
  ADD COLUMN IF NOT EXISTS `targeting` JSON NULL COMMENT 'db-design 标 JSON';

-- ad_creative（1 列）
ALTER TABLE ad_creative
  ADD COLUMN IF NOT EXISTS `ad_no` VARCHAR(36) NULL COMMENT '约定:业务键';

-- ad_impression（8 列）
ALTER TABLE ad_impression
  ADD COLUMN IF NOT EXISTS `ad_no` VARCHAR(36) NULL COMMENT '约定:业务键',
  ADD COLUMN IF NOT EXISTS `created_at` DATETIME(3) NULL COMMENT '既有DDL同名列×120',
  ADD COLUMN IF NOT EXISTS `delivery_no` VARCHAR(36) NULL COMMENT '约定:业务键',
  ADD COLUMN IF NOT EXISTS `impressions` INT NULL COMMENT '约定:整数',
  ADD COLUMN IF NOT EXISTS `plays` INT NULL COMMENT '约定:整数',
  ADD COLUMN IF NOT EXISTS `slot_no` VARCHAR(36) NULL COMMENT '约定:业务键',
  ADD COLUMN IF NOT EXISTS `stat_date` DATE NULL COMMENT '人工定型：曝光按日聚合（db-design：impressions/plays/stat_date），日粒度用 DATE',
  ADD COLUMN IF NOT EXISTS `tenant_id` VARCHAR(36) NULL COMMENT '既有DDL同名列×105';

-- ad_placement（2 列）
ALTER TABLE ad_placement
  ADD COLUMN IF NOT EXISTS `ad_no` VARCHAR(36) NULL COMMENT '约定:业务键',
  ADD COLUMN IF NOT EXISTS `slot_no` JSON NULL COMMENT 'db-design 标 JSON';

-- ad_slot（3 列）
ALTER TABLE ad_slot
  ADD COLUMN IF NOT EXISTS `position` VARCHAR(32) NULL COMMENT '约定:枚举/短码',
  ADD COLUMN IF NOT EXISTS `size` VARCHAR(32) NULL COMMENT '约定:枚举/短码',
  ADD COLUMN IF NOT EXISTS `slot_no` VARCHAR(36) NULL COMMENT '约定:业务键';

-- agt_account（2 列）
ALTER TABLE agt_account
  ADD COLUMN IF NOT EXISTS `agent_name` VARCHAR(64) NULL COMMENT '既有DDL同名列×1',
  ADD COLUMN IF NOT EXISTS `login_phone` VARCHAR(32) NULL COMMENT '人工定型：手机号（含国际区号），明文脱敏值；完整明文在 pb_pii';

-- coupon_tpl（1 列）
ALTER TABLE coupon_tpl
  ADD COLUMN IF NOT EXISTS `currency` VARCHAR(8) NULL COMMENT '既有DDL同名列×31';

-- dev_ota_release（1 列）
ALTER TABLE dev_ota_release
  ADD COLUMN IF NOT EXISTS `lock_version` BIGINT NULL COMMENT '约定:整数';

-- dict_item（7 列）
ALTER TABLE dict_item
  ADD COLUMN IF NOT EXISTS `code` VARCHAR(32) NULL COMMENT '既有DDL同名列×3',
  ADD COLUMN IF NOT EXISTS `dict_no` VARCHAR(36) NULL COMMENT '约定:业务键',
  ADD COLUMN IF NOT EXISTS `enabled` TINYINT(1) NULL COMMENT '既有DDL同名列×1',
  ADD COLUMN IF NOT EXISTS `group_code` VARCHAR(32) NULL COMMENT '人工定型：字典分组码，短码',
  ADD COLUMN IF NOT EXISTS `label` VARCHAR(128) NULL COMMENT '既有DDL同名列×1',
  ADD COLUMN IF NOT EXISTS `label_ar` VARCHAR(128) NULL COMMENT '人工定型：三语文本列，与 label 同宽',
  ADD COLUMN IF NOT EXISTS `label_en` VARCHAR(128) NULL COMMENT '人工定型：三语文本列，与 label 同宽';

-- fin_invoice（2 列）
ALTER TABLE fin_invoice
  ADD COLUMN IF NOT EXISTS `payee_no` VARCHAR(36) NULL COMMENT '既有DDL同名列×4',
  ADD COLUMN IF NOT EXISTS `payee_type` VARCHAR(16) NULL COMMENT '既有DDL同名列×3';

-- gw_command_log（7 列）
ALTER TABLE gw_command_log
  ADD COLUMN IF NOT EXISTS `confirmed_at` DATETIME(3) NULL COMMENT '约定:时间列(实体用String映射)',
  ADD COLUMN IF NOT EXISTS `operator` VARCHAR(64) NULL COMMENT '既有DDL同名列×3',
  ADD COLUMN IF NOT EXISTS `payload` JSON NULL COMMENT 'db-design 标 JSON',
  ADD COLUMN IF NOT EXISTS `retry` INT NULL COMMENT '约定:整数',
  ADD COLUMN IF NOT EXISTS `sent_at` DATETIME(3) NULL COMMENT '既有DDL同名列×3',
  ADD COLUMN IF NOT EXISTS `slot_index` JSON NULL COMMENT 'db-design 标 JSON',
  ADD COLUMN IF NOT EXISTS `sn` VARCHAR(64) NULL COMMENT '既有DDL同名列×4';

-- gw_device_binding（3 列）
ALTER TABLE gw_device_binding
  ADD COLUMN IF NOT EXISTS `bound_at` DATETIME(3) NULL COMMENT '既有DDL同名列×1',
  ADD COLUMN IF NOT EXISTS `raw_identity` VARCHAR(128) NULL COMMENT '人工定型：供应商原始标识，形态各厂商不一，留宽；进 UK(vendor_code,raw_identity)',
  ADD COLUMN IF NOT EXISTS `vendor_code` VARCHAR(32) NULL COMMENT '既有DDL同名列×8';

-- gw_message_log（5 列）
ALTER TABLE gw_message_log
  ADD COLUMN IF NOT EXISTS `cabinet_no` VARCHAR(36) NULL COMMENT '既有DDL同名列×18',
  ADD COLUMN IF NOT EXISTS `event_type` JSON NULL COMMENT 'db-design 标 JSON',
  ADD COLUMN IF NOT EXISTS `occurred_at` DATETIME(3) NULL COMMENT '既有DDL同名列×1',
  ADD COLUMN IF NOT EXISTS `parsed_event` JSON NULL COMMENT 'db-design 标 JSON',
  ADD COLUMN IF NOT EXISTS `result` VARCHAR(32) NULL COMMENT '约定:枚举/短码';

-- gw_vendor（1 列）
ALTER TABLE gw_vendor
  ADD COLUMN IF NOT EXISTS `device_count` INT NULL COMMENT '约定:整数';

-- gw_vendor_config（5 列）
ALTER TABLE gw_vendor_config
  ADD COLUMN IF NOT EXISTS `api_base` VARCHAR(256) NULL COMMENT '既有DDL同名列×2',
  ADD COLUMN IF NOT EXISTS `app_key` VARCHAR(64) NULL COMMENT '既有DDL同名列×1',
  ADD COLUMN IF NOT EXISTS `app_secret` VARCHAR(255) NULL COMMENT '人工定型：[KMS] 密文/掩码，非明文（db-design 标 [KMS]）',
  ADD COLUMN IF NOT EXISTS `ip_whitelist` JSON NULL COMMENT 'db-design 标 JSON',
  ADD COLUMN IF NOT EXISTS `verify_key` VARCHAR(255) NULL COMMENT '人工定型：[KMS] 验签密钥，与 app_secret 必须分列（合一则无法单独轮换）';

-- iam_audit_log（3 列）
ALTER TABLE iam_audit_log
  ADD COLUMN IF NOT EXISTS `actor_name` VARCHAR(128) NULL COMMENT '约定:名称',
  ADD COLUMN IF NOT EXISTS `target_no` JSON NULL COMMENT 'db-design 标 JSON',
  ADD COLUMN IF NOT EXISTS `target_type` JSON NULL COMMENT 'db-design 标 JSON';

-- iam_employee（3 列）
ALTER TABLE iam_employee
  ADD COLUMN IF NOT EXISTS `email` VARCHAR(128) NULL COMMENT '人工定型：邮箱',
  ADD COLUMN IF NOT EXISTS `role_no` VARCHAR(36) NULL COMMENT '既有DDL同名列×3',
  ADD COLUMN IF NOT EXISTS `user_id` VARCHAR(36) NULL COMMENT '人工定型：pb_auth 凭据引用（业务键宽度）';

-- inv_transfer（7 列）
ALTER TABLE inv_transfer
  ADD COLUMN IF NOT EXISTS `from_name` VARCHAR(128) NULL COMMENT '约定:名称',
  ADD COLUMN IF NOT EXISTS `from_ref` VARCHAR(36) NULL COMMENT '人工定型：调拨起点业务键，配合 from_type 解释（WAREHOUSE/SITE/LOCATION）',
  ADD COLUMN IF NOT EXISTS `from_type` VARCHAR(32) NULL COMMENT '约定:枚举/短码',
  ADD COLUMN IF NOT EXISTS `operator_no` VARCHAR(36) NULL COMMENT '约定:业务键',
  ADD COLUMN IF NOT EXISTS `to_name` VARCHAR(128) NULL COMMENT '约定:名称',
  ADD COLUMN IF NOT EXISTS `to_ref` VARCHAR(36) NULL COMMENT '人工定型：调拨终点业务键，配合 to_type',
  ADD COLUMN IF NOT EXISTS `to_type` VARCHAR(32) NULL COMMENT '约定:枚举/短码';

-- loc_site_lifecycle（1 列）
ALTER TABLE loc_site_lifecycle
  ADD COLUMN IF NOT EXISTS `owner_no` VARCHAR(36) NULL COMMENT '既有DDL同名列×1';

-- md_region（2 列）
ALTER TABLE md_region
  ADD COLUMN IF NOT EXISTS `city_count` INT NULL COMMENT '既有DDL同名列×1',
  ADD COLUMN IF NOT EXISTS `name_en` VARCHAR(64) NULL COMMENT '既有DDL同名列×3';

-- notify_template（4 列）
ALTER TABLE notify_template
  ADD COLUMN IF NOT EXISTS `lang` VARCHAR(8) NULL COMMENT '既有DDL同名列×2',
  ADD COLUMN IF NOT EXISTS `name` VARCHAR(64) NULL COMMENT '既有DDL同名列×12',
  ADD COLUMN IF NOT EXISTS `params` JSON NULL COMMENT 'db-design 标 JSON',
  ADD COLUMN IF NOT EXISTS `scene` JSON NULL COMMENT 'db-design 标 JSON';

-- ord_event_log（1 列）
ALTER TABLE ord_event_log
  ADD COLUMN IF NOT EXISTS `operator` VARCHAR(64) NULL COMMENT '既有DDL同名列×3';

-- pay_auth（2 列）
ALTER TABLE pay_auth
  ADD COLUMN IF NOT EXISTS `c_user_no` VARCHAR(36) NULL COMMENT '既有DDL同名列×26',
  ADD COLUMN IF NOT EXISTS `expire_at` DATETIME(3) NULL COMMENT '既有DDL同名列×2';

-- pay_refund（1 列）
ALTER TABLE pay_refund
  ADD COLUMN IF NOT EXISTS `ord_refund_no` VARCHAR(36) NULL COMMENT '约定:业务键';

-- price_rule（7 列）
ALTER TABLE price_rule
  ADD COLUMN IF NOT EXISTS `currency` VARCHAR(8) NULL COMMENT '既有DDL同名列×31',
  ADD COLUMN IF NOT EXISTS `day_cap` DECIMAL(18,2) NULL COMMENT '约定:金额',
  ADD COLUMN IF NOT EXISTS `dimension` VARCHAR(16) NULL COMMENT '既有DDL同名列×2',
  ADD COLUMN IF NOT EXISTS `free_mins` INT NULL COMMENT '约定:整数',
  ADD COLUMN IF NOT EXISTS `location_name` VARCHAR(128) NULL COMMENT '约定:名称',
  ADD COLUMN IF NOT EXISTS `match_ref` VARCHAR(36) NULL COMMENT '人工定型：匹配对象业务键，配合 dimension（SCENE/LOCATION/SITE）',
  ADD COLUMN IF NOT EXISTS `unit_price` DECIMAL(18,2) NULL COMMENT '既有DDL同名列×1';

-- share_record（4 列）
ALTER TABLE share_record
  ADD COLUMN IF NOT EXISTS `dimension` VARCHAR(16) NULL COMMENT '既有DDL同名列×2',
  ADD COLUMN IF NOT EXISTS `payee_name` VARCHAR(128) NULL COMMENT '既有DDL同名列×1',
  ADD COLUMN IF NOT EXISTS `rate` DECIMAL(5,4) NULL COMMENT '既有DDL同名列×2',
  ADD COLUMN IF NOT EXISTS `status` VARCHAR(16) NULL COMMENT '既有DDL同名列×72';

-- share_rule（3 列）
ALTER TABLE share_rule
  ADD COLUMN IF NOT EXISTS `currency` VARCHAR(8) NULL COMMENT '既有DDL同名列×31',
  ADD COLUMN IF NOT EXISTS `formula` JSON NULL COMMENT 'db-design 标 JSON',
  ADD COLUMN IF NOT EXISTS `payee_name` VARCHAR(128) NULL COMMENT '既有DDL同名列×1';

-- stl_settlement（1 列）
ALTER TABLE stl_settlement
  ADD COLUMN IF NOT EXISTS `payee_name` VARCHAR(128) NULL COMMENT '既有DDL同名列×1';

-- usr_coupon（1 列）
ALTER TABLE usr_coupon
  ADD COLUMN IF NOT EXISTS `expire_at` DATETIME(3) NULL COMMENT '既有DDL同名列×2';

-- usr_membership（3 列）
ALTER TABLE usr_membership
  ADD COLUMN IF NOT EXISTS `auto_renew` TINYINT(1) NULL COMMENT '既有DDL同名列×1',
  ADD COLUMN IF NOT EXISTS `level` VARCHAR(16) NULL COMMENT '既有DDL同名列×2',
  ADD COLUMN IF NOT EXISTS `points` INT NULL COMMENT '约定:整数';

-- usr_wallet（1 列）
ALTER TABLE usr_wallet
  ADD COLUMN IF NOT EXISTS `frozen_amount` DECIMAL(18,2) NULL COMMENT '约定:金额';

-- usr_wallet_txn（5 列）
ALTER TABLE usr_wallet_txn
  ADD COLUMN IF NOT EXISTS `c_user_no` VARCHAR(36) NULL COMMENT '既有DDL同名列×26',
  ADD COLUMN IF NOT EXISTS `currency` VARCHAR(8) NULL COMMENT '既有DDL同名列×31',
  ADD COLUMN IF NOT EXISTS `title` VARCHAR(128) NULL COMMENT '既有DDL同名列×5',
  ADD COLUMN IF NOT EXISTS `txn_no` VARCHAR(36) NULL COMMENT '约定:业务键',
  ADD COLUMN IF NOT EXISTS `type` VARCHAR(16) NULL COMMENT '既有DDL同名列×7';

-- wo_dispatch（1 列）
ALTER TABLE wo_dispatch
  ADD COLUMN IF NOT EXISTS `assignee_no` VARCHAR(36) NULL COMMENT '约定:业务键';

-- wo_handle（1 列）
ALTER TABLE wo_handle
  ADD COLUMN IF NOT EXISTS `assignee_no` JSON NULL COMMENT 'db-design 标 JSON';

-- wo_inspection_plan（4 列）
ALTER TABLE wo_inspection_plan
  ADD COLUMN IF NOT EXISTS `active` TINYINT(1) NULL COMMENT '既有DDL同名列×3',
  ADD COLUMN IF NOT EXISTS `assignee_no` VARCHAR(36) NULL COMMENT '约定:业务键',
  ADD COLUMN IF NOT EXISTS `frequency` VARCHAR(32) NULL COMMENT '约定:枚举/短码',
  ADD COLUMN IF NOT EXISTS `next_at` DATETIME(3) NULL COMMENT '约定:时间列(实体用String映射)';

-- wo_sla（1 列）
ALTER TABLE wo_sla
  ADD COLUMN IF NOT EXISTS `escalated_at` DATETIME(3) NULL COMMENT '约定:时间列(实体用String映射)';
