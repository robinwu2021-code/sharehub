-- ============================================================
-- V170 退款冲回分成（商家一期：营收口径「净交易额 = 交易额 − 退款」在分成上的落地）
--
-- 退款批准后，按退款占实收的比例给原分成方各写一条**负数**分成明细（refund_no 指向退款单），
-- 状态 PENDING、账期取冲回当月 —— 原分成已结算的，冲回进下一张结算单；未结算的，同一张单里相抵。
-- 不改原行：原行是当时的事实，改了就对不上已经发出去的结算单。
--
-- 唯一键要能容下冲回行：原键 (order_no, dimension, payee_no, basis) 下，同一单同一分成方只能有一条，
-- 冲回行会撞它。加一列 reversal_key = COALESCE(refund_no, '')（全程非空，约束真实生效）进键：
--   · 原行 reversal_key = ''，幂等语义不变（V37：outbox 重投不会多记一遍）；
--   · 冲回行按退款单号区分，同一笔退款重投也只冲一次，两笔部分退款各冲各的。
-- 本文件只用 MySQL 与 MariaDB 都认的语法。
-- ============================================================

ALTER TABLE share_record
  ADD COLUMN refund_no VARCHAR(36) NULL COMMENT '冲回来源退款单号；非空 = 本行是退款冲回（金额为负）',
  ADD COLUMN reversal_key VARCHAR(36) AS (COALESCE(refund_no, '')) STORED
    COMMENT '原行取空串、冲回行取退款单号；进唯一键，全程非空';

ALTER TABLE share_record
  DROP INDEX uk_srec_order_payee_basis,
  ADD UNIQUE KEY uk_srec_order_payee_basis_rev (order_no, dimension, payee_no, basis, reversal_key);

-- sys_outbox.event_type 词表加 ORDER_REFUNDED（退款已批准，finance 据此冲回分成）
ALTER TABLE sys_outbox
  MODIFY COLUMN event_type VARCHAR(64) NOT NULL
  COMMENT 'ASSET_ASSIGNED/CONTRACT_SIGNED/ORDER_SETTLED/CABINET_WENT_LIVE/DEVICE_SIGNAL/WORK_ORDER_COMPLETED/SITE_CLOSED/AGENT_STATUS_CHANGED/ORDER_ARREARS/APPROVAL_REQUESTED/TICKET_REQUESTED/OPSKIT_TICKET_COMMAND/ORDER_REFUNDED';
