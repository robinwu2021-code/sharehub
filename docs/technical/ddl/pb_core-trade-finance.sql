-- ============================================================
-- powerbank · pb_core 建表：订单 ord_ · 计费 price_ · 支付 pay_(nearpay 引用)
--                          账务 acct_ · 分润 share_ · 结算 stl_
-- 对齐 db-design.md §5 / TDD-trade-rental / 系统领域模型 D6-D9 / ADR-005(支付委托 nearpay)
-- ============================================================
SET NAMES utf8mb4;

-- ---------- D6 订单 ord_ ----------
CREATE TABLE ord_rent (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_no         VARCHAR(36)  NOT NULL,
  tenant_id        VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id        VARCHAR(36)      NULL,
  c_user_no        VARCHAR(36)  NOT NULL,
  cabinet_no       VARCHAR(36)  NOT NULL COMMENT '借出机柜',
  return_cabinet_no VARCHAR(36)     NULL COMMENT '归还机柜(异地归还)',
  powerbank_no     VARCHAR(36)      NULL,
  site_no          VARCHAR(36)      NULL COMMENT '借出站点(归属/分润)',
  agent_no         VARCHAR(36)      NULL COMMENT '归属代理(冗余·数据权限过滤)',
  price_plan_no    VARCHAR(36)      NULL,
  status           VARCHAR(16)  NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/DISPENSING/IN_USE/RETURNED/SETTLED/CLOSED/EXCEPTION',
  rent_start_at    DATETIME(3)      NULL,
  rent_end_at      DATETIME(3)      NULL,
  duration_min     INT              NULL,
  fee_amount       DECIMAL(18,2) NOT NULL DEFAULT 0,
  deposit_amount   DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency         VARCHAR(8)   NOT NULL DEFAULT 'AED',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version          BIGINT       NOT NULL DEFAULT 0,
  deleted          TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_ord_tenant_status (tenant_id, status),
  KEY idx_ord_user (c_user_no),
  KEY idx_ord_cabinet (cabinet_no),
  KEY idx_ord_site (site_no),
  KEY idx_ord_agent (tenant_id, agent_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租借订单(聚合根)';

CREATE TABLE ord_event_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_no    VARCHAR(36) NOT NULL,
  from_status VARCHAR(16)     NULL,
  to_status   VARCHAR(16) NOT NULL,
  event       VARCHAR(32) NOT NULL,
  data        JSON            NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_oel_order (order_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态流水(append)';

-- ---------- D7 计费 price_ ----------
CREATE TABLE price_plan (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  plan_no      VARCHAR(36)  NOT NULL,
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  name         VARCHAR(64)  NOT NULL,
  free_minutes INT          NOT NULL DEFAULT 0,
  unit_minutes INT          NOT NULL DEFAULT 30,
  unit_price   DECIMAL(18,2) NOT NULL DEFAULT 0,
  cap_daily    DECIMAL(18,2) NOT NULL DEFAULT 0,
  cap_total    DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '总封顶=买断价',
  currency     VARCHAR(8)   NOT NULL DEFAULT 'AED',
  scope        VARCHAR(32)  NOT NULL DEFAULT 'DEFAULT',
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT       NOT NULL DEFAULT 0,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_plan_no (plan_no),
  KEY idx_plan_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计费模板';

CREATE TABLE price_rule (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  rule_no    VARCHAR(36) NOT NULL,
  tenant_id  VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  plan_no    VARCHAR(36) NOT NULL COMMENT '差异化指向的模板',
  site_no    VARCHAR(36)     NULL COMMENT '按站点取价',
  scene_type VARCHAR(32)     NULL COMMENT '按场景取价',
  priority   INT         NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_rule_no (rule_no),
  KEY idx_prule_site (site_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='差异化定价(按站点/场景)';

-- ---------- D8 支付 pay_（委托 nearpay,仅引用+状态镜像）----------
CREATE TABLE pay_order (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  pay_no        VARCHAR(36)  NOT NULL,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  order_no      VARCHAR(36)  NOT NULL,
  c_user_no     VARCHAR(36)  NOT NULL,
  type          VARCHAR(16)  NOT NULL COMMENT 'DEPOSIT/RENT/BUYOUT',
  amount        DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency      VARCHAR(8)   NOT NULL DEFAULT 'AED',
  status        VARCHAR(16)  NOT NULL DEFAULT 'INIT' COMMENT 'INIT/PAYING/PAID/FAILED/CLOSED',
  nearpay_txn_no VARCHAR(64)     NULL COMMENT 'nearpay 交易引用',
  paid_at       DATETIME(3)      NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_no (pay_no),
  KEY idx_pay_order (order_no),
  KEY idx_pay_nearpay (nearpay_txn_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付引用(映射 nearpay)';

CREATE TABLE pay_auth (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  auth_no         VARCHAR(36)  NOT NULL,
  tenant_id       VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  order_no        VARCHAR(36)  NOT NULL,
  freeze_amount   DECIMAL(18,2) NOT NULL DEFAULT 0,
  captured_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  status          VARCHAR(16)  NOT NULL DEFAULT 'FROZEN' COMMENT 'FROZEN/CAPTURED/RELEASED',
  nearpay_auth_no VARCHAR(64)      NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version         BIGINT       NOT NULL DEFAULT 0,
  deleted         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_auth_no (auth_no),
  KEY idx_auth_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='免押编排(实际冻结在 nearpay)';

CREATE TABLE pay_refund (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  refund_no         VARCHAR(36)  NOT NULL,
  tenant_id         VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  pay_no            VARCHAR(36)  NOT NULL,
  amount            DECIMAL(18,2) NOT NULL DEFAULT 0,
  reason            VARCHAR(256)     NULL,
  status            VARCHAR(16)  NOT NULL DEFAULT 'INIT' COMMENT 'INIT/SUCCESS/FAILED',
  nearpay_refund_no VARCHAR(64)      NULL,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version           BIGINT       NOT NULL DEFAULT 0,
  deleted           TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_refund_no (refund_no),
  KEY idx_refund_pay (pay_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款引用';

CREATE TABLE pay_event_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  source      VARCHAR(16) NOT NULL DEFAULT 'NEARPAY',
  ref_no      VARCHAR(64) NOT NULL COMMENT 'nearpay 单号',
  event_type  VARCHAR(32) NOT NULL,
  raw         JSON            NULL,
  processed   TINYINT(1)  NOT NULL DEFAULT 0,
  received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_event (ref_no, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='nearpay 结果事件留痕(幂等)';

-- ---------- D9 账务 acct_ · 分润 share_ · 结算 stl_ ----------
CREATE TABLE acct_account (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  account_no VARCHAR(36) NOT NULL,
  tenant_id  VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  owner_type VARCHAR(16) NOT NULL COMMENT 'PLATFORM/VENUE/AGENT/DEPOSIT',
  owner_no   VARCHAR(36)     NULL,
  acct_type  VARCHAR(24) NOT NULL COMMENT '现金/应付/收入/负债',
  balance    DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency   VARCHAR(8)  NOT NULL DEFAULT 'AED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_account_no (account_no),
  KEY idx_acct_owner (owner_type, owner_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记账账户';

CREATE TABLE acct_ledger (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  entry_no   VARCHAR(36)  NOT NULL,
  tenant_id  VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  voucher_no VARCHAR(36)  NOT NULL COMMENT '凭证(一笔业务借贷成对)',
  order_no   VARCHAR(36)      NULL,
  account    VARCHAR(36)  NOT NULL COMMENT 'account_no',
  direction  VARCHAR(8)   NOT NULL COMMENT 'DEBIT/CREDIT',
  amount     DECIMAL(18,2) NOT NULL,
  currency   VARCHAR(8)   NOT NULL DEFAULT 'AED',
  summary    VARCHAR(128)     NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_entry_no (entry_no),
  KEY idx_ledger_voucher (voucher_no),
  KEY idx_ledger_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='复式分录(append)';

CREATE TABLE share_rule (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  rule_no    VARCHAR(36) NOT NULL,
  tenant_id  VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  dimension  VARCHAR(16) NOT NULL COMMENT 'VENUE/AGENT',
  payee_no   VARCHAR(36)     NULL COMMENT '分成方(venue_no/agent_no)',
  mode       VARCHAR(16) NOT NULL DEFAULT 'LEDGER' COMMENT 'CHANNEL_SPLIT/LEDGER',
  rate       DECIMAL(5,4) NOT NULL DEFAULT 0,
  priority   INT         NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT      NOT NULL DEFAULT 0,
  deleted    TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_share_rule_no (rule_no),
  KEY idx_srule_payee (dimension, payee_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分润规则';

CREATE TABLE share_record (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  record_no  VARCHAR(36) NOT NULL,
  tenant_id  VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  order_no   VARCHAR(36) NOT NULL,
  payee_type VARCHAR(16) NOT NULL COMMENT 'VENUE/AGENT/PLATFORM',
  payee_no   VARCHAR(36)     NULL,
  amount     DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency   VARCHAR(8)  NOT NULL DEFAULT 'AED',
  mode       VARCHAR(16) NOT NULL DEFAULT 'LEDGER',
  settle_no  VARCHAR(36)     NULL COMMENT '归属结算单',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_share_record_no (record_no),
  KEY idx_srec_order (order_no),
  KEY idx_srec_payee (payee_type, payee_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='逐单分润记录';

CREATE TABLE stl_settlement (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  settle_no    VARCHAR(36)  NOT NULL,
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  payee_type   VARCHAR(16)  NOT NULL COMMENT 'VENUE/AGENT',
  payee_no     VARCHAR(36)  NOT NULL,
  period       VARCHAR(16)  NOT NULL COMMENT '2026-07',
  total_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency     VARCHAR(8)   NOT NULL DEFAULT 'AED',
  status       VARCHAR(16)  NOT NULL DEFAULT 'GEN' COMMENT 'GEN/CONFIRMED/PAID',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_settle_no (settle_no),
  UNIQUE KEY uk_settle_period (payee_type, payee_no, period),
  KEY idx_stl_payee (payee_type, payee_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算单';

CREATE TABLE stl_withdrawal (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  withdraw_no VARCHAR(36) NOT NULL,
  tenant_id  VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  payee_type VARCHAR(16) NOT NULL,
  payee_no   VARCHAR(36) NOT NULL,
  amount     DECIMAL(18,2) NOT NULL DEFAULT 0,
  currency   VARCHAR(8)  NOT NULL DEFAULT 'AED',
  status     VARCHAR(16) NOT NULL DEFAULT 'APPLY' COMMENT 'APPLY/AUDIT/PAYING/PAID/FAILED',
  nearpay_payout_no VARCHAR(64) NULL COMMENT 'nearpay 打款单',
  applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_withdraw_no (withdraw_no),
  KEY idx_wd_payee (payee_type, payee_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现';
