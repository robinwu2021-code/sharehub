-- ============================================================
-- V187 第 6 批配置（运营方原始需求 C09 服务费 / C11 固定额分成，2026-10-29；用户定：未确认的一律做成配置）
--
-- 缺省全部 = 现行行为：服务费 0（不收），单均固定额关（只按比例分）。
--   · rent.service_fee.*：借出时并进订单价格快照，改了只对之后的单生效；只在有应付时收（免费时段 / 全额免单不收）
--   · finance.share.fixed_enabled：开后商家佣金里 dimension=ORDER_COUNT 的「单均固定额」才真正生成分润
--     （此前这一列存得进、从没被读 —— SHR-02）
-- 本文件只用 MySQL 与 MariaDB 都认的语法。
-- ============================================================
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'rent.service_fee.amount', '服务费（每单，借出时定格）；0 = 不收。运营方原文举例 AED 0.60 / 0.90，未定', '0', '计费'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'rent.service_fee.amount');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'rent.service_fee.vat_applicable', '服务费是否计 VAT：1 计 / 0 免（发票只对租金部分拆税）', '1', '计费'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'rent.service_fee.vat_applicable');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'rent.service_fee.in_share_base', '服务费是否进分成基数：1 进 / 0 不进（从分成基数里扣掉）', '0', '计费'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'rent.service_fee.in_share_base');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'rent.service_fee.payer', '服务费谁付：USER 加到用户应付上（只在有应付时收）/ PLATFORM 平台承担、不进订单', 'USER', '计费'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'rent.service_fee.payer');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'finance.share.fixed_enabled', '商家单均固定额分成：1 开（按商家佣金配置里 ORDER_COUNT 的固定额逐单分，取代比例）/ 0 关（现行，只按比例）', '0', '财务'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'finance.share.fixed_enabled');
