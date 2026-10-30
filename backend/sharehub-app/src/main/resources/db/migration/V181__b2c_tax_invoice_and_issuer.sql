-- ============================================================
-- V181 开票主体可配 + B2C 税务发票（运营方原始需求 C01 / RQ-INV，第 3 批，2026-10-29）
--
-- 用户定：未确认的问题一律做成配置。
--   · 开票主体（Jun Sheng / Junverse 未定）→ sys_tax_setting 补法定名称、地址两列，与既有 TRN、抬头
--     一起构成主体；用哪个国家的那一行由 sys_param invoice.issuer_country 定（缺省 AE）。
--     主体缺法定名称 / 地址 / TRN 任一项 → 不开票，记 BLOCKED 并写明缺哪项，不编一个主体出来。
--   · 是否给 C 端开票、何时开 → sys_param：invoice.b2c.enabled（缺省 0 关，与现行一致）、
--     invoice.b2c.trigger（ON_CHARGE 每次收款即开 / ON_SETTLE 结单才开，超时已扣款的单等归还）、
--     invoice.credit_note.on_refund（缺省 1：退款批准即开贷项通知单冲红）、invoice.b2c.prefix（INV）。
--
-- ord_tax_invoice 是租赁订单的税务票据（发票 + 贷项通知单同表，kind 区分）。
-- 与既有两张表分工：fin_invoice = 对 B 端（合作方/商家）的应收发票；usr_invoice = C 端发起的开票申请。
-- source_key 唯一（PAY:<payNo> / REFUND:<refundNo>）—— 同一笔收款、同一笔退款只出一张票，重试安全。
-- 主体、税率、是否含税整份快照进票据：改设置只影响之后开的票。
-- 本文件只用 MySQL 与 MariaDB 都认的语法。
-- ============================================================
ALTER TABLE sys_tax_setting
  ADD COLUMN issuer_legal_name VARCHAR(256) NULL COMMENT '开票主体法定名称（C01）；空 = 不能开 B2C 税务发票',
  ADD COLUMN issuer_address    VARCHAR(512) NULL COMMENT '开票主体注册地址（C01）';

CREATE TABLE IF NOT EXISTS ord_tax_invoice (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  invoice_no        VARCHAR(36)   NOT NULL COMMENT '票号：INV-YYYY-000001 / CN-YYYY-000001；BLOCKED 的也占号便于追踪',
  tenant_id         VARCHAR(36)   NOT NULL DEFAULT 'MAIN',
  kind              VARCHAR(16)   NOT NULL DEFAULT 'INVOICE' COMMENT 'INVOICE/CREDIT_NOTE',
  ref_invoice_no    VARCHAR(36)       NULL COMMENT '贷项通知单冲的那张发票',
  source_key        VARCHAR(80)   NOT NULL COMMENT '幂等：PAY:<payNo> / REFUND:<refundNo>',
  order_no          VARCHAR(36)   NOT NULL,
  c_user_no         VARCHAR(36)       NULL,
  pay_no            VARCHAR(36)       NULL,
  refund_no         VARCHAR(36)       NULL,
  issuer_country    VARCHAR(8)        NULL COMMENT '以下为开票时主体快照',
  issuer_legal_name VARCHAR(256)      NULL,
  issuer_address    VARCHAR(512)      NULL,
  issuer_trn        VARCHAR(64)       NULL,
  issuer_title      VARCHAR(256)      NULL,
  tax_rate_percent  DECIMAL(5,2)  NOT NULL DEFAULT 0,
  tax_included      TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '1 = 金额含税（从总额倒推税额）',
  net_amount        DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '贷项通知单为负数',
  tax_amount        DECIMAL(18,2) NOT NULL DEFAULT 0,
  gross_amount      DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency          VARCHAR(8)    NOT NULL DEFAULT 'AED',
  line_desc         VARCHAR(256)      NULL COMMENT '票面行描述（租借时段、机柜）',
  status            VARCHAR(16)   NOT NULL DEFAULT 'ISSUED' COMMENT 'ISSUED/BLOCKED',
  fail_reason       VARCHAR(256)      NULL COMMENT 'BLOCKED 的原因，如 ISSUER_MISSING:trn',
  issued_at         DATETIME(3)       NULL,
  created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by        VARCHAR(64)       NULL,
  updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by        VARCHAR(64)       NULL,
  version           BIGINT        NOT NULL DEFAULT 0,
  deleted           TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_otinv_no (tenant_id, invoice_no),
  UNIQUE KEY uk_otinv_source (tenant_id, source_key),
  KEY idx_otinv_order (order_no),
  KEY idx_otinv_user (tenant_id, c_user_no, created_at),
  KEY idx_otinv_status (tenant_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租赁订单税务票据（B2C 发票 + 贷项通知单）';

INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'invoice.b2c.enabled', 'C 端租赁税务发票：1 开 / 0 关', '0', '发票'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'invoice.b2c.enabled');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'invoice.b2c.trigger', '开票时点：ON_CHARGE 每次收款即开 / ON_SETTLE 结单才开', 'ON_CHARGE', '发票'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'invoice.b2c.trigger');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'invoice.credit_note.on_refund', '退款批准后自动开贷项通知单：1 开 / 0 关', '1', '发票'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'invoice.credit_note.on_refund');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'invoice.b2c.prefix', '发票号前缀（贷项通知单固定 CN）', 'INV', '发票'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'invoice.b2c.prefix');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'invoice.issuer_country', '开票主体取哪国税务设置行（ISO 两位码，无则用 * 默认行）', 'AE', '发票'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'invoice.issuer_country');
