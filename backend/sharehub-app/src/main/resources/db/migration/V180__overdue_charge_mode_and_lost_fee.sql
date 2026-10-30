-- ============================================================
-- V180 超时扣款可配置 + 遗失费独立（运营方原始需求 K4 / K5 / C04，2026-10-29 用户定：未确认的一律做成配置）
--
-- 两种超时扣款模型，由 sys_param rent.overdue.mode 选：
--   · CAP_AMOUNT（缺省，现行）：累计费用达到计费方案总封顶 → 买断结单，充电宝转给用户；
--   · TIME_LIMIT（原文）：借出超过最长时限（billing.overdueHours）→ 发起最高收费，订单进
--     OVERDUE_CHARGED「已扣款未归还」；之后归还只更新归还信息并结单，**不冲销已收费用**（原文 AC-03）。
-- 扣款时刻另存 overdue_charged_at，rent_end_at 只留给真实归还 —— 此前两者共用一列（RQ-BIL-03）。
--
-- 遗失费 price_plan.lost_fee：空 = 与总封顶合一（现行）；填了 = 用户主动买断 / 遗失按它收，
-- 与最高租赁收费独立（原文 RQ-APP-06）。影响钱的取值随订单快照定格，改了只对之后的订单生效。
-- 本文件只用 MySQL 与 MariaDB 都认的语法。
-- ============================================================
ALTER TABLE ord_order
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'CREATED'
    COMMENT 'CREATED/DISPENSING/IN_USE/OVERDUE_CHARGED/RETURNED/SETTLED/CLOSED/EXCEPTION',
  ADD COLUMN overdue_charged_at DATETIME(3) NULL COMMENT '超时最高收费发起时刻（TIME_LIMIT 模式）；与归还时刻 rent_end_at 分开';

ALTER TABLE price_plan
  ADD COLUMN lost_fee DECIMAL(18,2) NULL COMMENT '遗失费；空 = 与总封顶 cap_total 合一（现行），填了 = 独立收取';

INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'rent.overdue.mode', '超时扣款模型：CAP_AMOUNT 累计触顶买断 / TIME_LIMIT 到时限发起最高收费', 'CAP_AMOUNT', '计费'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'rent.overdue.mode');
