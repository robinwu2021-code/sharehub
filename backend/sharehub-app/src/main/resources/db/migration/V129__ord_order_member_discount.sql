-- 会员折扣落痕（TDD-会员套餐 §2）。
--
-- 与 coupon_no / coupon_amount 同一套路：等级答「凭什么打折」，金额答「折了多少」。
-- 后者事后推不出来 —— 权益配置会改，而折扣额还取决于当时的应收。
-- 不记的话账单上「应收 − 实付」会差一块钱而无从解释，且不会报错。
ALTER TABLE ord_order
    ADD COLUMN member_level VARCHAR(20) NULL COMMENT '会员等级（源 usr_membership.level），空=非会员' AFTER waived_amount,
    ADD COLUMN member_discount_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '会员折扣额；买断单恒为 0' AFTER member_level;
