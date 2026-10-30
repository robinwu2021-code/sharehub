-- ============================================================
-- V183 商家分成自开票（运营方原始需求 C10 / SHR-04，2026-10-29；用户定：未确认的问题一律做成配置）
--
-- 自开票（self-billing）：平台付给商家分成时，由平台（收货方）代商家（供货方）开出税务发票。
-- 成立的前提是供方是 VAT 登记户 —— 所以先给商家档案补 TRN；没有 TRN 的商家不开（不是错误，INFO 记一笔）。
--
-- 配置 sys_param：
--   · finance.share.self_billing（缺省 0 关，与现行一致）—— 开 = 每笔提现确认到账时出一张自开票单据；
--   · finance.share.self_billing.prefix（SB）。
-- 税率与含税口径取平台开票主体同一国的税务设置（invoice.issuer_country），**分成付款额视为含税**
-- （付出去的就是全部，税从里面倒推）；随单据快照。
--
-- stl_self_bill：withdraw_no 唯一 —— 同一笔付款只出一张，重试与重复确认都安全。
-- 本文件只用 MySQL 与 MariaDB 都认的语法。
-- ============================================================
ALTER TABLE agt_agent
  ADD COLUMN vat_trn VARCHAR(32) NULL COMMENT '商家 VAT 税号（TRN）；空 = 非 VAT 登记户，不出自开票（C10）';

CREATE TABLE IF NOT EXISTS stl_self_bill (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  bill_no           VARCHAR(36)   NOT NULL COMMENT 'SB-YYYY-000001',
  tenant_id         VARCHAR(36)   NOT NULL DEFAULT 'MAIN',
  withdraw_no       VARCHAR(36)   NOT NULL COMMENT '→ stl_withdrawal.withdraw_no（一笔付款一张）',
  agent_no          VARCHAR(36)   NOT NULL COMMENT '供方 = 商家',
  supplier_name     VARCHAR(128)      NULL COMMENT '以下为出单时快照',
  supplier_address  VARCHAR(256)      NULL,
  supplier_trn      VARCHAR(32)       NULL,
  recipient_name    VARCHAR(256)      NULL COMMENT '收货方 = 平台开票主体',
  recipient_address VARCHAR(512)      NULL,
  recipient_trn     VARCHAR(64)       NULL,
  tax_rate_percent  DECIMAL(5,2)  NOT NULL DEFAULT 0,
  net_amount        DECIMAL(18,2) NOT NULL DEFAULT 0,
  tax_amount        DECIMAL(18,2) NOT NULL DEFAULT 0,
  gross_amount      DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency          VARCHAR(8)    NOT NULL DEFAULT 'AED',
  pay_ref           VARCHAR(64)       NULL COMMENT '付款流水号',
  issued_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by        VARCHAR(64)       NULL,
  updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by        VARCHAR(64)       NULL,
  version           BIGINT        NOT NULL DEFAULT 0,
  deleted           TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ssb_no (tenant_id, bill_no),
  UNIQUE KEY uk_ssb_withdraw (tenant_id, withdraw_no),
  KEY idx_ssb_agent (tenant_id, agent_no, issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家分成自开票单据（C10）';

INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'finance.share.self_billing', '商家分成自开票：1 开（提现到账即出单据，仅对有 TRN 的商家）/ 0 关', '0', '发票'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'finance.share.self_billing');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'finance.share.self_billing.prefix', '自开票单据号前缀', 'SB', '发票'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'finance.share.self_billing.prefix');
