-- 两张「多值拆表」的关联表清掉软删行，并让它们从此走物理删除。
--
-- 症状：支付渠道 / 充值套餐**第二次保存就 500**
--   Duplicate entry 'NEARPAY-COUNTRY-AE' for key 'uk_pay_channel_scope'
--   Duplicate entry 'PKG0001-AE'         for key 'uk_rpkg_market'
--
-- 成因：两张表都继承 BaseEntity ⇒ MyBatis-Plus 的 delete() 是逻辑删除（SET deleted=1），
-- 而唯一键里**没有 deleted**：
--   UNIQUE KEY uk_pay_channel_scope (channel_code, scope_type, scope_value)
--   UNIQUE KEY uk_rpkg_market       (package_no, country_code)
-- 「全量重写」的先删后插于是撞在自己刚软删的那行上。
-- 第一次配置正常、只增不减也正常 —— 所以只测「配置一次」的路径全都是绿的。
--
-- 实体侧已改成 @TableName(excludeProperty = "deleted")：这两张表不参与逻辑删除。
-- 一行只是「某某在某某可用」的事实，没有独立审计价值，该留痕的是父记录。
--
-- 这里必须先把存量软删行**物理清掉**：实体不再认 deleted 之后，
-- 那些行会重新对查询可见 —— 等于把运营取消过的市场/国家悄悄恢复。
DELETE FROM pay_channel_scope        WHERE deleted = 1;
DELETE FROM usr_recharge_pkg_market  WHERE deleted = 1;
