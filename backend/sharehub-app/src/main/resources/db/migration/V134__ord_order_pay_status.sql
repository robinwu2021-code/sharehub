SET NAMES utf8mb4;
-- ════════════════════════════════════════════════════════════════════
-- C-BL-04 欠费补缴：订单的「收没收到」（2026-09-27 拍板：先收钱再分账）
--
-- 【缺口】结算从不请款：returnOrderAt 算完钱就 SETTLE 并发结算事件，全仓没有一处在结算时
-- 调支付；ord_order 也没有任何字段表达「这张单的钱收到了没有」。于是「欠费」在数据上不成立，
-- 「欠费拦下次借出」无从谈起，而 finance 按应收生成了分润 —— 那笔钱从没收过。
--
-- 【新列】结算时请款（RentOrderServiceImpl#collect），成功 PAID，失败 UNPAID（欠费）。
-- 进行中的单为空。补缴成功后由 UNPAID 转 PAID。见 TDD-欠费补缴与结算请款.md。
--
-- 【存量】已结算 / 已关闭的历史单一律 PAID：它们产生于桩支付阶段、从没真实请款过；
-- 照实标成欠费会让所有老用户借不了、订单列表挂满「欠费」—— 那不是修好了 C-BL-04，
-- 是把骨架缺口摊到用户脸上。paid_at / pay_no 如实留空（没有那笔支付）。
-- ════════════════════════════════════════════════════════════════════

ALTER TABLE ord_order
    ADD COLUMN IF NOT EXISTS pay_status  VARCHAR(16)   NULL COMMENT 'PAID 已收 / UNPAID 欠费；进行中为空' AFTER amount,
    ADD COLUMN IF NOT EXISTS paid_amount DECIMAL(18,2) NULL COMMENT '实收金额' AFTER pay_status,
    ADD COLUMN IF NOT EXISTS paid_at     DATETIME(3)   NULL COMMENT '收款时刻（UTC）' AFTER paid_amount,
    ADD COLUMN IF NOT EXISTS pay_no      VARCHAR(36)   NULL COMMENT '收到这笔钱的 pay_order.pay_no' AFTER paid_at;

CREATE INDEX IF NOT EXISTS idx_ord_user_pay ON ord_order (c_user_no, pay_status);

UPDATE ord_order
   SET pay_status = 'PAID', paid_amount = amount
 WHERE status IN ('SETTLED', 'CLOSED') AND pay_status IS NULL;
