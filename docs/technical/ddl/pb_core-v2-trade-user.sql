-- ============================================================
-- powerbank · pb_core 建表（v2 增量）：订单 ord_ · 计费 price_ · 结算/对账/发票 stl_ recon_ fin_
--                                     用户风控与充值 usr_ · 营销 mkt_ · 客服 cs_ · C端专属 usr_
-- 对齐 db-design.md **v2**（2026-07-29）§1.3~§1.7 通用约定 / §五 trade / §六 user / §十 索引基线
-- 前置：pb_core-trade-finance.sql · pb_core-user-ad-workorder.sql · pb_core-loc-agt-iam.sql
--       · pb_core-device-gateway.sql（本文件只建这四份里**尚不存在**的表）
--
-- ------------------------------------------------------------
-- 【已存在 → 本文件跳过】（grep 现有 4 份 sql 得出）
--   price_rule       —— 已在 pb_core-trade-finance.sql 建（v1 形态：rule_no/plan_no/site_no/scene_type/priority）。
--                       ⚠ 与 db-design v2 §5.2 规格有差距，缺 dimension/match_ref/scene/location_name/
--                       free_mins/unit_price/day_cap/currency；本文件不改它，补列见文件末「待补 ALTER」。
--   stl_settlement   —— 已建（本文件只建其明细表 stl_settlement_detail）。
--   usr_credit       —— 已建（v1 形态 score/blacklisted/reason）。v2 要求补 risk_no/risk_level/flagged_at，
--                       且 blacklisted 移出到本文件的 usr_blacklist；改造不在本文件范围。
--   coupon_tpl       —— 已建（v1 缺 currency，v2 §1.5 要求补；见文件末「待补 ALTER」）。
--   usr_coupon       —— 已建（缺 §十 要求的属主索引；见文件末「待补 ALTER」）。
--   pay_refund / pay_order / pay_auth / pay_event_log / acct_* / share_* / stl_withdrawal —— 均已建。
--
-- 【本文件新建 32 表】
--   ord_exception ord_complaint ord_refund ord_reservation ord_deposit
--   price_schedule price_plan_scope
--   stl_settlement_detail recon_task recon_diff fin_invoice fin_invoice_item
--   usr_blacklist usr_free_whitelist
--   usr_recharge_pkg usr_recharge_pkg_market usr_recharge_order
--   mkt_notice mkt_campaign mkt_push mkt_referral
--   cs_ticket cs_session cs_message
--   usr_favorite usr_message usr_push_token usr_notify_pref
--   usr_invoice_title usr_invoice usr_logoff usr_consent
-- ============================================================
SET NAMES utf8mb4;

-- ============================================================
-- 一、订单域 ord_（db-design §5.1）
-- ============================================================

-- 异常订单（菜单：订单管理·异常订单）
CREATE TABLE ord_exception (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  exception_no  VARCHAR(36)  NOT NULL,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id     VARCHAR(36)      NULL,
  order_no      VARCHAR(36)  NOT NULL,
  c_user_no     VARCHAR(36)  NOT NULL,
  cabinet_no    VARCHAR(36)      NULL,
  type          VARCHAR(24)  NOT NULL COMMENT 'NOT_EJECTED/NOT_RETURNED/OVERTIME_BUYOUT/DOUBLE_CHARGE',
  amount        DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '涉及金额',
  currency      VARCHAR(8)   NOT NULL DEFAULT 'AED',
  status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/HANDLED',
  handled_by    VARCHAR(36)      NULL COMMENT 'employee_no',
  handled_at    DATETIME(3)      NULL COMMENT '空=尚未处理',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exception_no (exception_no),
  KEY idx_oexc_tenant_status (tenant_id, status, created_at),
  KEY idx_oexc_order (order_no),
  KEY idx_oexc_user (tenant_id, c_user_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常订单';

-- 投诉订单（菜单：订单管理·投诉订单）· 转工单幂等落在 wo_order.source_ref(=complaint_no)
CREATE TABLE ord_complaint (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  complaint_no   VARCHAR(36)  NOT NULL,
  tenant_id      VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id      VARCHAR(36)      NULL,
  order_no       VARCHAR(36)  NOT NULL,
  c_user_no      VARCHAR(36)  NOT NULL,
  issue_type     VARCHAR(24)  NOT NULL COMMENT 'BILLING_DISPUTE/NOT_EJECTED/NOT_RETURNED/DEVICE_FAULT/OTHER',
  description    VARCHAR(1024)    NULL,
  screenshot_url VARCHAR(512)     NULL COMMENT '截图证据',
  submitted_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/RESOLVED/REJECTED',
  handler_name   VARCHAR(64)      NULL COMMENT '处理人快照名(不回溯)',
  handled_at     DATETIME(3)      NULL COMMENT '空=尚未处理',
  resolution     VARCHAR(16)      NULL COMMENT 'REFUND/COMPENSATE/REJECT/EXPLAINED',
  resolution_note VARCHAR(512)    NULL,
  wo_no          VARCHAR(36)      NULL COMMENT '转工单(幂等由 wo_order.source_ref UK 保证)',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version        BIGINT       NOT NULL DEFAULT 0,
  deleted        TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_complaint_no (complaint_no),
  KEY idx_ocpl_tenant_status (tenant_id, status, created_at),
  KEY idx_ocpl_order (order_no),
  KEY idx_ocpl_user (tenant_id, c_user_no, created_at),
  KEY idx_ocpl_wo (wo_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投诉订单';

-- 退款审批单（业务侧·聚合根）· 与 pay_refund(渠道执行引用)1:1，审批通过才生成后者
CREATE TABLE ord_refund (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  refund_no       VARCHAR(36)  NOT NULL,
  tenant_id       VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id       VARCHAR(36)      NULL,
  order_no        VARCHAR(36)  NOT NULL,
  c_user_no       VARCHAR(36)  NOT NULL,
  amount          DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency        VARCHAR(8)   NOT NULL DEFAULT 'AED',
  reason          VARCHAR(256)     NULL,
  applicant_name  VARCHAR(64)      NULL COMMENT '申请人快照名(不回溯)',
  applied_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/EXECUTED/FAILED',
  auditor_name    VARCHAR(64)      NULL COMMENT '审批人快照名',
  audited_at      DATETIME(3)      NULL COMMENT '空=尚未审批',
  reject_reason   VARCHAR(256)     NULL COMMENT '驳回必填(资金审批合规)',
  idempotency_key VARCHAR(64)  NOT NULL COMMENT '幂等键(前端生成) db-design §1.6 防重复退款',
  psp_txn_no      VARCHAR(64)      NULL COMMENT 'PSP 交易号(冗余便于对账)',
  pay_refund_no   VARCHAR(36)      NULL COMMENT '→ pay_refund.refund_no(渠道执行凭证)',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version         BIGINT       NOT NULL DEFAULT 0,
  deleted         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ord_refund_no (refund_no),
  UNIQUE KEY uk_ord_refund_idem (idempotency_key),
  KEY idx_orfd_tenant_status (tenant_id, status, created_at),
  KEY idx_orfd_order (order_no),
  KEY idx_orfd_user (tenant_id, c_user_no, created_at),
  KEY idx_orfd_pay_refund (pay_refund_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款审批单(业务侧·聚合根,幂等)';

-- 预约订单（菜单：订单管理·预约订单）
CREATE TABLE ord_reservation (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  reservation_no VARCHAR(36)  NOT NULL,
  tenant_id      VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id      VARCHAR(36)      NULL,
  c_user_no      VARCHAR(36)  NOT NULL,
  type           VARCHAR(16)  NOT NULL COMMENT 'BORROW/RETURN',
  site_no        VARCHAR(36)      NULL,
  site_name      VARCHAR(128)     NULL COMMENT '站点名快照(不回溯)',
  cabinet_no     VARCHAR(36)      NULL,
  reserved_from  DATETIME(3)  NOT NULL COMMENT '预约窗口起',
  reserved_to    DATETIME(3)  NOT NULL COMMENT '预约窗口止',
  hold_fee       DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '占位费',
  currency       VARCHAR(8)   NOT NULL DEFAULT 'AED',
  status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/FULFILLED/EXPIRED/CANCELLED',
  order_no       VARCHAR(36)      NULL COMMENT '履约后回填 ord_rent.order_no',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version        BIGINT       NOT NULL DEFAULT 0,
  deleted        TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_reservation_no (reservation_no),
  KEY idx_orsv_tenant_status (tenant_id, status, created_at),
  KEY idx_orsv_user (tenant_id, c_user_no, created_at),
  KEY idx_orsv_site (site_no, reserved_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约订单(借/还)';

-- 押金与欠费（菜单：订单管理·押金与欠费）· 冻结执行态见 pay_auth
CREATE TABLE ord_deposit (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  deposit_no     VARCHAR(36)  NOT NULL,
  tenant_id      VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id      VARCHAR(36)      NULL,
  order_no       VARCHAR(36)  NOT NULL,
  c_user_no      VARCHAR(36)  NOT NULL,
  amount         DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '押金额',
  currency       VARCHAR(8)   NOT NULL DEFAULT 'AED',
  status         VARCHAR(16)  NOT NULL DEFAULT 'HELD' COMMENT 'HELD/RELEASED/BOUGHT_OUT/ARREARS',
  arrears_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '欠费额(status=ARREARS)',
  released_at    DATETIME(3)      NULL COMMENT '空=尚未释放',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version        BIGINT       NOT NULL DEFAULT 0,
  deleted        TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_deposit_no (deposit_no),
  UNIQUE KEY uk_deposit_order (order_no) COMMENT '一单一押金(ER: ord_rent 1─0..1 ord_deposit)',
  KEY idx_odep_tenant_status (tenant_id, status, created_at),
  KEY idx_odep_user (tenant_id, c_user_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='押金与欠费';

-- ============================================================
-- 二、计费 price_（db-design §5.2 / §1.7 多值列拆表）
-- ============================================================

-- 活动/时段价（菜单：计费定价·活动时段价）
CREATE TABLE price_schedule (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  rule_no    VARCHAR(36)  NOT NULL,
  tenant_id  VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id  VARCHAR(36)      NULL,
  name       VARCHAR(64)  NOT NULL,
  period     VARCHAR(128) NOT NULL COMMENT '时段/节假日表达式,如 18:00-23:00 或 HOLIDAY:EID',
  multiplier DECIMAL(6,4) NOT NULL DEFAULT 1.0000 COMMENT '倍率(可 >1,非 0..1 比率)',
  active     TINYINT(1)   NOT NULL DEFAULT 1,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT       NOT NULL DEFAULT 0,
  deleted    TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_sched_no (rule_no),
  KEY idx_psched_tenant (tenant_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动/时段价';

-- 计费模板适用范围（§1.7：前端 PricePlan.scope CSV → 拆表）
CREATE TABLE price_plan_scope (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id  VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  plan_no    VARCHAR(36) NOT NULL,
  scope_type VARCHAR(16) NOT NULL COMMENT 'SITE/SCENE/ALL',
  scope_ref  VARCHAR(64) NOT NULL DEFAULT '*' COMMENT 'site_no / scene_type / *(ALL)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT      NOT NULL DEFAULT 0,
  deleted    TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_plan_scope (plan_no, scope_type, scope_ref),
  KEY idx_pscope_ref (scope_type, scope_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计费模板适用范围(多值拆表)';

-- ============================================================
-- 三、结算明细 · 对账 · 发票（db-design §5.5 / §1.7）
-- ============================================================

-- 结算明细（stl_settlement 1─* 本表）
CREATE TABLE stl_settlement_detail (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id  VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  settle_no  VARCHAR(36) NOT NULL,
  ref_type   VARCHAR(16) NOT NULL COMMENT 'ORDER/SHARE',
  ref_no     VARCHAR(36) NOT NULL COMMENT 'order_no / share_record.record_no',
  amount     DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency   VARCHAR(8)  NOT NULL DEFAULT 'AED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT      NOT NULL DEFAULT 0,
  deleted    TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stl_detail (settle_no, ref_type, ref_no),
  KEY idx_stld_ref (ref_type, ref_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算明细';

-- 对账任务（菜单：财务管理·对账）
CREATE TABLE recon_task (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_no      VARCHAR(36)  NOT NULL,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id     VARCHAR(36)      NULL,
  channel       VARCHAR(32)  NOT NULL COMMENT '→ pay_channel.channel_code',
  period        CHAR(7)      NOT NULL COMMENT '账期 YYYY-MM',
  bill_date     DATE         NOT NULL COMMENT '账单日(仅日期)',
  nearpay_total DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '渠道侧合计',
  ledger_total  DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '账务侧合计',
  diff          DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '差额=nearpay_total-ledger_total',
  currency      VARCHAR(8)   NOT NULL DEFAULT 'AED',
  status        VARCHAR(16)  NOT NULL DEFAULT 'MATCHED' COMMENT 'MATCHED/DIFF',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_recon_batch_no (batch_no),
  UNIQUE KEY uk_recon_channel_date (tenant_id, channel, bill_date),
  KEY idx_recon_period (tenant_id, period, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账任务(批次)';

-- 对账差错
CREATE TABLE recon_diff (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id  VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  batch_no   VARCHAR(36) NOT NULL,
  pay_no     VARCHAR(36)     NULL COMMENT '→ pay_order.pay_no(单边账时可空)',
  diff_type  VARCHAR(24) NOT NULL COMMENT 'MISSING_LOCAL/MISSING_CHANNEL/AMOUNT_MISMATCH/STATUS_MISMATCH/DUPLICATE',
  amount     DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '差额',
  currency   VARCHAR(8)  NOT NULL DEFAULT 'AED',
  detail     JSON            NULL,
  resolved   TINYINT(1)  NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT      NOT NULL DEFAULT 0,
  deleted    TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_rdiff_batch (batch_no, resolved),
  KEY idx_rdiff_pay (pay_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差错';

-- 发票（运营侧开票管理，菜单：财务管理·发票）· orderNos 拆到 fin_invoice_item
CREATE TABLE fin_invoice (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  invoice_no VARCHAR(36)  NOT NULL,
  tenant_id  VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id  VARCHAR(36)      NULL,
  payee_name VARCHAR(128) NOT NULL COMMENT '抬头快照(不回溯)',
  amount     DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency   VARCHAR(8)   NOT NULL DEFAULT 'AED',
  vat_trn    VARCHAR(32)      NULL COMMENT '税号 TRN',
  status     VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ISSUED/VOID',
  issued_at  DATETIME(3)      NULL COMMENT '空=尚未开具',
  file_url   VARCHAR(512)     NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT       NOT NULL DEFAULT 0,
  deleted    TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_fin_invoice_no (invoice_no),
  KEY idx_finv_tenant_status (tenant_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发票(运营侧开票)';

-- 发票-订单关联（§1.7：fin_invoice.order_nos 与 usr_invoice.order_nos 共用本表）
CREATE TABLE fin_invoice_item (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id    VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  invoice_no   VARCHAR(36) NOT NULL COMMENT 'fin_invoice.invoice_no 或 usr_invoice.invoice_no',
  invoice_side VARCHAR(8)  NOT NULL DEFAULT 'OPS' COMMENT 'OPS(fin_invoice)/USER(usr_invoice) 区分号段来源',
  order_no     VARCHAR(36) NOT NULL,
  amount       DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '该单计入开票的金额',
  currency     VARCHAR(8)  NOT NULL DEFAULT 'AED',
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT      NOT NULL DEFAULT 0,
  deleted      TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_fin_invoice_item (invoice_side, invoice_no, order_no),
  KEY idx_finvi_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发票明细(发票×订单)';

-- ============================================================
-- 四、用户风控 usr_（db-design §6.1）
-- ============================================================

-- 黑名单（菜单：用户管理·黑名单）· 解除=软删语义 status=RELEASED，不物理删
CREATE TABLE usr_blacklist (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  blacklist_no   VARCHAR(36)  NOT NULL,
  tenant_id      VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id      VARCHAR(36)      NULL,
  c_user_no      VARCHAR(36)  NOT NULL,
  reason         VARCHAR(256)     NULL,
  blacklisted_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  blacklisted_by VARCHAR(36)      NULL COMMENT 'employee_no',
  released_at    DATETIME(3)      NULL COMMENT '空=尚未解除',
  released_by    VARCHAR(36)      NULL,
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/RELEASED',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version        BIGINT       NOT NULL DEFAULT 0,
  deleted        TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_blacklist_no (blacklist_no),
  KEY idx_ubl_user (tenant_id, c_user_no, status),
  KEY idx_ubl_tenant_status (tenant_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户黑名单(前缀 BL,勿与 NBL 触达拉黑混淆)';

-- 免费用户白名单（菜单：用户管理·免费用户白名单）· ord_rent.free_reason 回指本表 reason
CREATE TABLE usr_free_whitelist (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  whitelist_no VARCHAR(36)  NOT NULL,
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id    VARCHAR(36)      NULL,
  c_user_no    VARCHAR(36)  NOT NULL,
  reason       VARCHAR(24)  NOT NULL COMMENT 'INTERNAL_TEST/VIP/BD_DEMO/MERCHANT_SELF',
  quota_type   VARCHAR(16)  NOT NULL DEFAULT 'UNLIMITED' COMMENT 'UNLIMITED/TIMES/AMOUNT',
  quota_value  DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT 'TIMES=次数,AMOUNT=金额(按 currency)',
  used_value   DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '已用量,口径同 quota_value',
  currency     VARCHAR(8)   NOT NULL DEFAULT 'AED' COMMENT 'quota_type=AMOUNT 时生效',
  valid_from   DATE             NULL,
  valid_to     DATE             NULL,
  granted_by   VARCHAR(36)      NULL COMMENT 'employee_no',
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/EXPIRED/REVOKED',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT       NOT NULL DEFAULT 0,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_whitelist_no (whitelist_no),
  KEY idx_ufw_user (tenant_id, c_user_no, status),
  KEY idx_ufw_tenant_status (tenant_id, status, valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='免费用户白名单';

-- ============================================================
-- 五、充值 usr_recharge*（db-design §6.2 / §1.7 markets 拆表）
-- ============================================================

CREATE TABLE usr_recharge_pkg (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  package_no  VARCHAR(36)  NOT NULL,
  tenant_id   VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id   VARCHAR(36)      NULL,
  name        VARCHAR(64)  NOT NULL,
  pay_amount  DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '实付',
  gift_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '赠送',
  currency    VARCHAR(8)   NOT NULL DEFAULT 'AED',
  valid_days  INT              NULL COMMENT '到账余额有效期(天),空=永久',
  sort_no     INT          NOT NULL DEFAULT 0,
  status      VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version     BIGINT       NOT NULL DEFAULT 0,
  deleted     TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_recharge_pkg_no (package_no),
  KEY idx_rpkg_tenant_status (tenant_id, status, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值套餐';

-- 套餐适用市场（§1.7：RechargePackage.markets CSV → 拆表）
CREATE TABLE usr_recharge_pkg_market (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id    VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  package_no   VARCHAR(36) NOT NULL,
  country_code CHAR(2)     NOT NULL COMMENT 'ISO alpha-2 → md_market_country.country_code',
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT      NOT NULL DEFAULT 0,
  deleted      TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpkg_market (package_no, country_code),
  KEY idx_rpkgm_country (country_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值套餐适用市场(多值拆表)';

CREATE TABLE usr_recharge_order (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  recharge_no   VARCHAR(36)  NOT NULL,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id     VARCHAR(36)      NULL,
  c_user_no     VARCHAR(36)  NOT NULL,
  nickname      VARCHAR(64)      NULL COMMENT '昵称快照(不回溯)',
  package_no    VARCHAR(36)      NULL COMMENT '空=自定义金额充值',
  pay_amount    DECIMAL(18,2) NOT NULL DEFAULT 0,
  gift_amount   DECIMAL(18,2) NOT NULL DEFAULT 0,
  credit_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '实际入账=pay+gift',
  currency      VARCHAR(8)   NOT NULL DEFAULT 'AED',
  channel_code  VARCHAR(32)      NULL COMMENT '→ pay_channel.channel_code',
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PAID/FAILED/REFUNDED',
  psp_txn_no    VARCHAR(64)      NULL COMMENT 'PSP 交易号(db-design 写作 psg_txn_no,按 ord_refund 口径统一为 psp_)',
  paid_at       DATETIME(3)      NULL COMMENT '空=尚未支付',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_recharge_no (recharge_no),
  KEY idx_rord_tenant_status (tenant_id, status, created_at),
  KEY idx_rord_user (tenant_id, c_user_no, created_at),
  KEY idx_rord_psp (psp_txn_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值订单';

-- ============================================================
-- 六、营销 mkt_（db-design §6.3）
-- ============================================================

-- 公告（菜单：营销管理·公告 / C端首页公告条）· 三语三列
CREATE TABLE mkt_notice (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  notice_no    VARCHAR(36)  NOT NULL,
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id    VARCHAR(36)      NULL,
  title        VARCHAR(128) NOT NULL COMMENT '中文',
  title_en     VARCHAR(128)     NULL,
  title_ar     VARCHAR(128)     NULL,
  content      TEXT             NULL COMMENT '中文',
  content_en   TEXT             NULL,
  content_ar   TEXT             NULL,
  type         VARCHAR(16)  NOT NULL DEFAULT 'SYSTEM' COMMENT 'SYSTEM/PROMO/MAINTENANCE',
  pinned       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '置顶',
  start_at     DATETIME(3)      NULL COMMENT '生效起',
  end_at       DATETIME(3)      NULL COMMENT '生效止',
  status       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/OFFLINE',
  published_by VARCHAR(36)      NULL COMMENT 'employee_no',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT       NOT NULL DEFAULT 0,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_notice_no (notice_no),
  KEY idx_ntc_publish (tenant_id, status, pinned, start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告(三语)';

-- 营销活动（菜单：营销管理·活动）· 注意与 ad_campaign(广告活动) 是两个实体
CREATE TABLE mkt_campaign (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  campaign_no VARCHAR(36)  NOT NULL,
  tenant_id   VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id   VARCHAR(36)      NULL,
  name        VARCHAR(64)  NOT NULL,
  kind        VARCHAR(24)  NOT NULL COMMENT 'NEW_USER/RECHARGE_GIFT/COUPON_PUSH/REFERRAL/FESTIVAL',
  rule        JSON             NULL COMMENT '活动规则(门槛/奖励/频次)',
  status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/RUNNING/ENDED',
  start_at    DATETIME(3)      NULL,
  end_at      DATETIME(3)      NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version     BIGINT       NOT NULL DEFAULT 0,
  deleted     TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mkt_campaign_no (campaign_no),
  KEY idx_mcmp_tenant_status (tenant_id, status, start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='营销活动';

-- 推送触达（菜单：营销管理·推送）
CREATE TABLE mkt_push (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  push_no    VARCHAR(36)  NOT NULL,
  tenant_id  VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id  VARCHAR(36)      NULL,
  title      VARCHAR(128) NOT NULL,
  content    TEXT             NULL,
  channel    VARCHAR(16)  NOT NULL DEFAULT 'APP_PUSH' COMMENT 'APP_PUSH/SUBSCRIBE',
  audience   JSON             NULL COMMENT '受众条件(分群 SEG/标签/全量)',
  sent_count INT          NOT NULL DEFAULT 0,
  status     VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SENT',
  sent_at    DATETIME(3)      NULL COMMENT '空=尚未发送',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT       NOT NULL DEFAULT 0,
  deleted    TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_push_no (push_no),
  KEY idx_mpush_tenant_status (tenant_id, status, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推送触达';

-- 邀请裂变（菜单：营销管理·裂变 / C-SH-01,03）
CREATE TABLE mkt_referral (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  invite_no  VARCHAR(36) NOT NULL,
  tenant_id  VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  region_id  VARCHAR(36)     NULL,
  inviter_no VARCHAR(36) NOT NULL COMMENT '邀请人 c_user_no',
  invitee_no VARCHAR(36)     NULL COMMENT '被邀请人 c_user_no(注册后回填)',
  reward     DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency   VARCHAR(8)  NOT NULL DEFAULT 'AED',
  status     VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/REWARDED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT      NOT NULL DEFAULT 0,
  deleted    TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_invite_no (invite_no),
  UNIQUE KEY uk_referral_invitee (tenant_id, invitee_no) COMMENT '一个新用户只能被一人邀请成功',
  KEY idx_mref_inviter (tenant_id, inviter_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请裂变';

-- ============================================================
-- 七、客服 cs_（db-design §6.5）
-- ============================================================

-- 报障受理（C端 POST /mp/user/report 落点）· 两个出口：wo_no 转工单 / refund_no 转退款
CREATE TABLE cs_ticket (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ticket_no  VARCHAR(36)  NOT NULL,
  tenant_id  VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id  VARCHAR(36)      NULL,
  c_user_no  VARCHAR(36)  NOT NULL,
  order_no   VARCHAR(36)      NULL,
  cabinet_no VARCHAR(36)      NULL,
  problem_no VARCHAR(36)      NULL COMMENT '→ md_problem.problem_no',
  issue      VARCHAR(512)     NULL COMMENT '用户描述',
  channel    VARCHAR(16)  NOT NULL DEFAULT 'APP' COMMENT 'APP/MP/H5/PHONE/EMAIL',
  status     VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/PROCESSING/CLOSED',
  handler_no VARCHAR(36)      NULL COMMENT 'employee_no',
  wo_no      VARCHAR(36)      NULL COMMENT '出口①转工单',
  refund_no  VARCHAR(36)      NULL COMMENT '出口②转退款 → ord_refund.refund_no',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT       NOT NULL DEFAULT 0,
  deleted    TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ticket_no (ticket_no),
  KEY idx_cstk_tenant_status (tenant_id, status, created_at),
  KEY idx_cstk_user (tenant_id, c_user_no, created_at),
  KEY idx_cstk_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报障受理';

CREATE TABLE cs_session (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  session_no   VARCHAR(36)  NOT NULL,
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id    VARCHAR(36)      NULL,
  c_user_no    VARCHAR(36)  NOT NULL,
  agent_name   VARCHAR(64)      NULL COMMENT '客服坐席快照名(不回溯)',
  last_message VARCHAR(512)     NULL COMMENT '最后一条消息摘要(列表直出)',
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CLOSED',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT       NOT NULL DEFAULT 0,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cs_session_no (session_no),
  KEY idx_csss_tenant_status (tenant_id, status, updated_at),
  KEY idx_csss_user (tenant_id, c_user_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服会话';

-- 会话消息（append：无 version/deleted，只 INSERT）
-- 分区：按 created_at 月分区（PARTITION BY RANGE COLUMNS(created_at)，运维脚本滚动建/删分区）
CREATE TABLE cs_message (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id   VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  session_no  VARCHAR(36) NOT NULL,
  sender_type VARCHAR(8)  NOT NULL COMMENT 'USER/AGENT',
  sender_no   VARCHAR(36)     NULL COMMENT 'c_user_no 或 employee_no',
  content     TEXT            NULL,
  attach      JSON            NULL COMMENT '图片/文件 URL 列表',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_csmsg_session (session_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服会话消息(append,月分区)';

-- ============================================================
-- 八、C 端专属 usr_（db-design §6.6）
-- ============================================================

-- 收藏门店（c-app /mp/user/favorites）
CREATE TABLE usr_favorite (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id  VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  c_user_no  VARCHAR(36) NOT NULL,
  site_no    VARCHAR(36) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT      NOT NULL DEFAULT 0,
  deleted    TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_favorite (c_user_no, site_no),
  KEY idx_ufav_user (tenant_id, c_user_no, created_at),
  KEY idx_ufav_site (site_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收藏门店';

-- 站内消息中心（C-MS-03）· read 是 MySQL 保留字，落列名 is_read
CREATE TABLE usr_message (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  message_no VARCHAR(36)  NOT NULL,
  tenant_id  VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  c_user_no  VARCHAR(36)  NOT NULL,
  type       VARCHAR(24)  NOT NULL COMMENT 'ORDER/WALLET/COUPON/SYSTEM/ACTIVITY/CS',
  title      VARCHAR(128) NOT NULL,
  body       TEXT             NULL,
  is_read    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '规格字段名 read,MySQL 保留字改名 is_read',
  read_at    DATETIME(3)      NULL COMMENT '空=尚未读',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT       NOT NULL DEFAULT 0,
  deleted    TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_message_no (message_no),
  KEY idx_umsg_user (tenant_id, c_user_no, created_at),
  KEY idx_umsg_unread (tenant_id, c_user_no, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内消息中心';

-- Push token 注册（C-MS-01）
CREATE TABLE usr_push_token (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id  VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  c_user_no  VARCHAR(36)  NOT NULL,
  platform   VARCHAR(16)  NOT NULL COMMENT 'APNS/FCM/UNIPUSH',
  token      VARCHAR(256) NOT NULL,
  device_id  VARCHAR(128)     NULL,
  active     TINYINT(1)   NOT NULL DEFAULT 1,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT       NOT NULL DEFAULT 0,
  deleted    TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_push_token (platform, token),
  KEY idx_uptk_user (tenant_id, c_user_no, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Push token 注册';

-- 通知偏好（C-MS-04）· 静默窗口用 CHAR(5) HH:mm
CREATE TABLE usr_notify_pref (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id   VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  c_user_no   VARCHAR(36) NOT NULL,
  category    VARCHAR(24) NOT NULL COMMENT 'ORDER/WALLET/COUPON/SYSTEM/ACTIVITY/CS',
  enabled     TINYINT(1)  NOT NULL DEFAULT 1,
  quiet_start CHAR(5)         NULL COMMENT '静默起 HH:mm',
  quiet_end   CHAR(5)         NULL COMMENT '静默止 HH:mm',
  lang        VARCHAR(8)  NOT NULL DEFAULT 'en' COMMENT 'zh/en/ar',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version     BIGINT      NOT NULL DEFAULT 0,
  deleted     TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_notify_pref (c_user_no, category),
  KEY idx_unpf_user (tenant_id, c_user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C端通知偏好';

-- 发票抬头（C-IV-02）
CREATE TABLE usr_invoice_title (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  title_no   VARCHAR(36)  NOT NULL,
  tenant_id  VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  c_user_no  VARCHAR(36)  NOT NULL,
  type       VARCHAR(16)  NOT NULL DEFAULT 'PERSONAL' COMMENT 'PERSONAL/COMPANY',
  title      VARCHAR(128) NOT NULL,
  vat_trn    VARCHAR(32)      NULL COMMENT '税号 TRN(COMPANY 必填)',
  is_default TINYINT(1)   NOT NULL DEFAULT 0,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT       NOT NULL DEFAULT 0,
  deleted    TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_invoice_title_no (title_no),
  KEY idx_uivt_user (tenant_id, c_user_no, is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C端发票抬头';

-- C端开票申请（C-IV-01/03）· orderNos 落 fin_invoice_item(invoice_side=USER)
CREATE TABLE usr_invoice (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  invoice_no VARCHAR(36)  NOT NULL,
  tenant_id  VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  c_user_no  VARCHAR(36)  NOT NULL,
  title_no   VARCHAR(36)  NOT NULL COMMENT '→ usr_invoice_title.title_no',
  title      VARCHAR(128)     NULL COMMENT '抬头快照(不回溯)',
  amount     DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency   VARCHAR(8)   NOT NULL DEFAULT 'AED',
  status     VARCHAR(16)  NOT NULL DEFAULT 'APPLIED' COMMENT 'APPLIED/ISSUED/REJECTED',
  file_url   VARCHAR(512)     NULL,
  applied_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  issued_at  DATETIME(3)      NULL COMMENT '空=尚未开具',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT       NOT NULL DEFAULT 0,
  deleted    TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_usr_invoice_no (invoice_no),
  KEY idx_uiv_user (tenant_id, c_user_no, created_at),
  KEY idx_uiv_status (tenant_id, status, applied_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C端开票申请';

-- 注销申请（C-AC-05,PDPL 冷静期）
CREATE TABLE usr_logoff (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id     VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  c_user_no     VARCHAR(36) NOT NULL,
  requested_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  cooling_until DATETIME(3) NOT NULL COMMENT '冷静期截止,期内可撤销',
  status        VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CANCELLED/DONE',
  purged_at     DATETIME(3)     NULL COMMENT '空=尚未清除',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT      NOT NULL DEFAULT 0,
  deleted       TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_ulof_user (tenant_id, c_user_no, requested_at) COMMENT '不设 UK：撤销后允许再次申请,PENDING 唯一性由应用层保证',
  KEY idx_ulof_cooling (status, cooling_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='注销申请(PDPL 冷静期)';

-- 同意与撤回留痕（C-AC-06,PDPL）· append + WORM：无 version/deleted，仅 INSERT
-- 分区：按 created_at 月分区，保留 7 年（db-design §十）
CREATE TABLE usr_consent (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id         VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  c_user_no         VARCHAR(36) NOT NULL,
  agreement_code    VARCHAR(64) NOT NULL COMMENT '协议标识 TOS/PRIVACY/MARKETING/...',
  agreement_version VARCHAR(32) NOT NULL COMMENT '协议版本(规格写作 version,避让 BaseEntity 乐观锁列名)',
  action            VARCHAR(8)  NOT NULL COMMENT 'GRANT/REVOKE',
  lang              VARCHAR(8)  NOT NULL DEFAULT 'en' COMMENT 'zh/en/ar',
  ip                VARCHAR(64)     NULL,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_ucst_user (tenant_id, c_user_no, created_at),
  KEY idx_ucst_agreement (agreement_code, agreement_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同意与撤回留痕(append,月分区,WORM)';

-- ============================================================
-- 九、对既有表的待补 ALTER（db-design v2 要求，涉及其它 sql 文件已建的表）
-- 说明：本文件只建新表，以下语句**已注释**，由 v2 改造批次统一执行，避免与既有 DDL 文件双写。
-- ============================================================
-- §十 属主查询索引（C端）：
-- ALTER TABLE usr_coupon     ADD KEY idx_coupon_owner (tenant_id, c_user_no, created_at);
-- ALTER TABLE usr_wallet_txn ADD COLUMN c_user_no VARCHAR(36) NOT NULL AFTER wallet_no,
--                            ADD KEY idx_wtxn_owner (wallet_no, c_user_no, created_at);
-- ALTER TABLE ord_rent       ADD KEY idx_ord_owner (tenant_id, c_user_no, created_at);
-- §1.5 金额必带币种：
-- ALTER TABLE coupon_tpl     ADD COLUMN currency VARCHAR(8) NOT NULL DEFAULT 'AED' AFTER threshold;
-- §5.2 price_rule 补齐 v2 关键列：
-- ALTER TABLE price_rule ADD COLUMN dimension VARCHAR(16) NOT NULL DEFAULT 'SITE' COMMENT 'SCENE/LOCATION/SITE',
--   ADD COLUMN match_ref VARCHAR(64) NULL, ADD COLUMN location_name VARCHAR(128) NULL,
--   ADD COLUMN free_mins INT NULL, ADD COLUMN unit_price DECIMAL(18,2) NULL,
--   ADD COLUMN day_cap DECIMAL(18,2) NULL, ADD COLUMN currency VARCHAR(8) NOT NULL DEFAULT 'AED';
-- §5.1 ord_rent 补 free_reason/waived_amount/coupon_no/location_name；§6.1 usr_credit 补 risk_no/risk_level/flagged_at。


-- ============================================================
-- 补：mbr_plan（db-design §6.3 有、v1 DDL 与 v2 三份脚本均漏建）
-- usr_membership 已存在于 pb_core-user-ad-workorder.sql，指向本表 plan_no。
-- ============================================================
CREATE TABLE mbr_plan (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  plan_no       VARCHAR(36)  NOT NULL                COMMENT '业务键',
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  name          VARCHAR(128) NOT NULL                COMMENT '方案名',
  name_en       VARCHAR(128)     NULL,
  name_ar       VARCHAR(128)     NULL,
  card_type     VARCHAR(16)  NOT NULL DEFAULT 'MONTHLY' COMMENT 'MONTHLY(包月)/TIMES(次卡)/RIGHTS(权益卡)',
  price         DECIMAL(18,2) NOT NULL DEFAULT 0     COMMENT '售价',
  currency      VARCHAR(8)   NOT NULL DEFAULT 'AED',
  period_days   INT              NULL                COMMENT '有效期天数(MONTHLY/RIGHTS)',
  times_total   INT              NULL                COMMENT '总次数(TIMES 卡)',
  rights        JSON             NULL                COMMENT '权益：免费时长/折扣率/免押提额',
  auto_renew    TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否支持自动续费',
  sort_no       INT          NOT NULL DEFAULT 0,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mbr_plan_no (plan_no),
  KEY idx_mbr_plan_status (tenant_id, status, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员方案';
