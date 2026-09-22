-- ============================================================
-- powerbank · 审计四列统一补齐（2026-07-30，用户定调）
--
-- 口径：所有表都要有「创建时间 / 创建人 / 更新时间 / 更新人」。
-- 填充由 `common/AuditMetaObjectHandler` 统一负责，业务代码不手写这四个字段
-- （审计字段的价值在「无一例外」，靠人记住迟早漏）。
--
-- 两类表区别处理：
--   · 可变表（84 张）→ 补 created_by + updated_by（created_at/updated_at 多数已有）
--   · append 只增表（17 张，以 db-design 的 `append` 标注为准）→ **只补 created_by**
--     理由：账本 / 审计 / 报文 / 指令流水 / 状态时间线在设计上永不 UPDATE
--     （acct_ledger 记错只能红冲、iam_audit_log 是 WORM），
--     补 updated_* 永远是 NULL，反而让读表的人以为是漏填。
--     若要改为「四列一律补齐」，把 append 段换成与可变表相同的两行即可。
--
-- ⚠️ append 表的判定**不能**用「DDL 里有没有 deleted 列」——
--    那会把 punchlist 里「DDL 漏了 version/deleted」的缺陷表（acct_account/usr_wallet/
--    ad_slot 等 30 张）误判成 append。判定源是 db-design 的显式 `append` 标注。
--
-- 幂等性：MySQL 无 ADD COLUMN IF NOT EXISTS，重复执行报 1060。请用迁移工具管版本。
-- ============================================================
SET NAMES utf8mb4;
USE pb_core;

-- ── 前置：可变表补 updated_at（这些表 DDL 原本就缺，否则下面的 AFTER updated_at 会失败）──
ALTER TABLE ad_advertiser            ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;
ALTER TABLE ad_creative              ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;
ALTER TABLE ad_placement             ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;
ALTER TABLE dev_shadow               ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;
ALTER TABLE dict_item                ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;
ALTER TABLE gw_device_binding        ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;
ALTER TABLE iam_employee_role        ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;
ALTER TABLE iam_permission           ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;
ALTER TABLE iam_role_perm            ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;
ALTER TABLE md_region                ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;
ALTER TABLE share_record             ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;
ALTER TABLE usr_identity             ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;
ALTER TABLE wo_sla                   ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;

-- ── 可变表（113 张）：补 created_by + updated_by ──
ALTER TABLE acct_account             ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE ad_advertiser            ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE ad_campaign              ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE ad_creative              ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE ad_placement             ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE ad_slot                  ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE agt_account              ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE agt_agent                ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE agt_agent_region         ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE agt_commission           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE coupon_tpl               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE cs_session               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE cs_ticket                ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE dev_alarm                ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE dev_alarm_code           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE dev_alarm_rule           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE dev_cabinet              ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE dev_code_batch           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE dev_ota_release          ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE dev_ota_rollout          ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE dev_ota_task             ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE dev_powerbank            ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE dev_shadow               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE dev_slot                 ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE dict_item                ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE fin_invoice              ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE fin_invoice_item         ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE gw_device_binding        ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE gw_vendor                ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE gw_vendor_config         ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE iam_data_scope           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE iam_dept                 ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE iam_employee             ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE iam_employee_role        ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE iam_menu                 ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE iam_permission           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE iam_role                 ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE iam_role_perm            ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE iam_staff_perf           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE inv_stock                ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE inv_transfer             ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE inv_transfer_item        ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE inv_warehouse            ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE loc_contract             ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE loc_lead                 ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE loc_location             ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE loc_site                 ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE loc_site_lifecycle       ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE loc_venue                ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE loc_venue_onboarding     ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE mbr_plan                 ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE md_bank                  ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE md_market_country        ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE md_problem               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE md_region                ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE mkt_campaign             ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE mkt_notice               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE mkt_push                 ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE mkt_referral             ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE notify_blacklist         ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE notify_template          ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE openapi_app              ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE ord_complaint            ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE ord_deposit              ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE ord_exception            ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE ord_refund               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE ord_rent                 ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE ord_reservation          ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE pay_auth                 ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE pay_channel              ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE pay_channel_scope        ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE pay_order                ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE pay_refund               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE price_plan               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE price_plan_scope         ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE price_rule               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE price_schedule           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE recon_diff               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE recon_task               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE share_record             ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE share_rule               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE stl_settlement           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE stl_settlement_detail    ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE stl_withdrawal           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE sys_app_version          ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE sys_biz_rule             ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE sys_login_setting        ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE sys_param                ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE sys_tax_setting          ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE tenant                   ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE tenant_config            ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_blacklist            ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_coupon               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_credit               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_favorite             ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_free_whitelist       ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_identity             ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_invoice              ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_invoice_title        ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_logoff               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_membership           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_message              ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_notify_pref          ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_push_token           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_recharge_order       ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_recharge_pkg         ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_recharge_pkg_market  ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_user                 ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE usr_wallet               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE wo_inspection_plan       ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE wo_order                 ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE wo_sla                   ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;
ALTER TABLE wo_sla_rule              ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at,
      ADD COLUMN updated_by VARCHAR(36) NULL COMMENT '更新人' AFTER updated_at;

-- ── append 只增表（18 张）：只补 created_by ──
ALTER TABLE acct_ledger              ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
ALTER TABLE ad_impression            ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
ALTER TABLE agt_assignment           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
ALTER TABLE cs_message               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
ALTER TABLE dev_alarm_notice         ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
ALTER TABLE dev_alert                ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
-- dev_heartbeat 原本无 created_at，只有领域时间戳 beat_at。两者语义不同且都有用：
--   beat_at = 事件发生时刻（设备/渠道侧时钟）；created_at = 服务端落库时刻。
--   二者之差可诊断时钟偏移与链路延迟，所以补 created_at 而非复用领域列。
ALTER TABLE dev_heartbeat ADD COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '落库时刻' AFTER beat_at,
      ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
ALTER TABLE gw_command_log           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
ALTER TABLE gw_message_log           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
ALTER TABLE iam_audit_log            ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
ALTER TABLE loc_site_lifecycle_log   ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
ALTER TABLE notify_log               ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
ALTER TABLE ord_event_log            ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
-- pay_event_log 原本无 created_at，只有领域时间戳 received_at。两者语义不同且都有用：
--   received_at = 事件发生时刻（设备/渠道侧时钟）；created_at = 服务端落库时刻。
--   二者之差可诊断时钟偏移与链路延迟，所以补 created_at 而非复用领域列。
ALTER TABLE pay_event_log ADD COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '落库时刻' AFTER received_at,
      ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
ALTER TABLE usr_consent              ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
ALTER TABLE usr_wallet_txn           ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
-- wo_dispatch 原本无 created_at，只有领域时间戳 dispatched_at。两者语义不同且都有用：
--   dispatched_at = 事件发生时刻（设备/渠道侧时钟）；created_at = 服务端落库时刻。
--   二者之差可诊断时钟偏移与链路延迟，所以补 created_at 而非复用领域列。
ALTER TABLE wo_dispatch ADD COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '落库时刻' AFTER dispatched_at,
      ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;
-- wo_handle 原本无 created_at，只有领域时间戳 handled_at。两者语义不同且都有用：
--   handled_at = 事件发生时刻（设备/渠道侧时钟）；created_at = 服务端落库时刻。
--   二者之差可诊断时钟偏移与链路延迟，所以补 created_at 而非复用领域列。
ALTER TABLE wo_handle ADD COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '落库时刻' AFTER handled_at,
      ADD COLUMN created_by VARCHAR(36) NULL COMMENT '创建人' AFTER created_at;

-- ── 存量回填 ──────────────────────────────────────────────
-- 历史行的真实操作人已不可考，统一标 'MIGRATED'，以区别于「漏填(NULL)」与「系统写入(SYSTEM)」。
-- 三值语义：MIGRATED=迁移前的历史数据 · SYSTEM=定时任务/设备事件/迁移作业 · NULL=有 bug 漏填了。
-- 批量生成回填语句：
--   SELECT CONCAT('UPDATE ',TABLE_NAME,' SET created_by=''MIGRATED'' WHERE created_by IS NULL;')
--   FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA='pb_core' AND COLUMN_NAME='created_by';
