-- 订单记下「这一单券抵了多少」。
--
-- 此前只有 coupon_no（挂了哪张券），抵扣额不落列 —— 于是：
--   · C 端订单详情的费用明细拼不出「券抵扣 -5」那一行，用户只看见总额变小了；
--   · 运营答不出「本月券让利多少」，与 waived_amount 当初被补上的理由是同一个。
-- 事后从 coupon_tpl 反推也不行：模板面额会改，折扣券的抵扣额还取决于当时的应收。
ALTER TABLE ord_order
  ADD COLUMN IF NOT EXISTS coupon_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '券抵扣金额';

-- 注释单独再落一次：ADD COLUMN IF NOT EXISTS 在列已存在时整条跳过，
-- 补注释要靠无条件的 MODIFY（V87 踩过：列先手工建了，注释就永远补不上）。
ALTER TABLE ord_order
  MODIFY COLUMN coupon_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '券抵扣金额';
