-- 订单人工干预留痕（前端契约 OrderIntervention）

SET NAMES utf8mb4;

-- ── 为什么必须建这张表 ──
-- 干预是**人工覆盖资金/状态**的动作：免单、补偿、强制归还、远程弹出。
-- 改造前 `RentOrderServiceImpl#intervene` 只把订单置 CLOSED，
-- **谁改的、为什么改、改之前是什么状态，一概不留** —— 事后无从追责，
-- 也无法回答「这一单为什么没收到钱」。审计留痕不是附加功能，是干预能力的前提。
--
-- append-only：同一单可以被干预多次（先补弹、后免单），覆盖式记录会丢掉过程。
CREATE TABLE IF NOT EXISTS ord_intervention (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  intervention_no  VARCHAR(36)  NOT NULL COMMENT '业务键，前缀 ITV',
  tenant_id        VARCHAR(36)  NULL,
  order_no         VARCHAR(36)  NOT NULL,
  action           VARCHAR(24)  NOT NULL COMMENT 'eject/force_return/waive/compensate/refund_apply',
  operator         VARCHAR(64)  NULL COMMENT '干预人',
  -- 原因必填：沿用退款审批口径。无原因的资金干预等于没有留痕 ——
  -- 「谁做的」能查到，「为什么」查不到，追责时仍然停在原地。
  reason           VARCHAR(500) NOT NULL,
  -- 涉及金额：免单减免额 / 补偿额 / 强制归还结算额；eject 无金额故可空
  amount           DECIMAL(18,4) NULL,
  currency         VARCHAR(8)   NULL,
  before_status    VARCHAR(24)  NOT NULL,
  after_status     VARCHAR(24)  NOT NULL COMMENT '与 before 相同 = 只留痕不改状态',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by       VARCHAR(36)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_intervention_no (intervention_no),
  KEY idx_intervention_order (tenant_id, order_no, created_at),
  KEY idx_intervention_action (tenant_id, action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单人工干预留痕（append）';
