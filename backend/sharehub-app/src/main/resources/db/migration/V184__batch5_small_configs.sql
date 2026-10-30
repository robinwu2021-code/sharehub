-- ============================================================
-- V184 第 5 批小配置（运营方原始需求 C03 / C08 / C11 / C12 / PAY-01，2026-10-29；用户定：未确认的一律做成配置）
--
-- 全部是 sys_param，缺省值 = 现行行为（或原文举例），所以本迁移本身不改变任何计费 / 分润结果：
--   · billing.unit_rounding —— 新建方案的计费周期取整缺省；每个方案可单独选（存在计时费用项 rounding 上）
--   · finance.rounding.scale / finance.share.rounding —— 分润金额小数位与舍入（现行 2 位、向下取整）
--   · finance.share.rate_min / rate_max —— 分成规则保存时的比例区间；缺省 0 / 1 不约束，原文 15%–50% 由运营开
--   · contract.expiry.* —— 合同到期提醒窗口与两档升级（现行 60 / 30 / 7 天）
--   · pay.default_channel —— 请款没指明渠道时记什么（此前 pay_order.channel_code 全空）
-- 影响钱的项随记录定格：分润明细生成即定格、方案取整随订单价格快照定格。
-- 本文件只用 MySQL 与 MariaDB 都认的语法。
-- ============================================================
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'billing.unit_rounding', '计费周期取整缺省（新建方案用）：CEIL 不足一个周期进一 / FLOOR 舍去 / HALF_UP 过半进一', 'CEIL', '计费'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'billing.unit_rounding');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'finance.rounding.scale', '分润金额小数位（0..4）', '2', '财务'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'finance.rounding.scale');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'finance.share.rounding', '分润舍入：DOWN 向下取整（合计不超基数，尾差归平台）/ HALF_UP 四舍五入', 'DOWN', '财务'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'finance.share.rounding');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'finance.share.rate_min', '分成比例下限（0..1）；运营方原文 0.15。缺省 0 = 不约束（现行）。比例 0 = 明确不分，不受约束', '0', '财务'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'finance.share.rate_min');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'finance.share.rate_max', '分成比例上限（0..1）；运营方原文 0.50。缺省 1 = 不约束（现行）', '1', '财务'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'finance.share.rate_max');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'contract.expiry.remind_days', '合同到期提醒窗口（天）', '60', '合同'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'contract.expiry.remind_days');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'contract.expiry.escalate_medium_days', '合同到期提醒升为中优先级（剩余天数）', '30', '合同'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'contract.expiry.escalate_medium_days');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'contract.expiry.escalate_high_days', '合同到期提醒升为高优先级（剩余天数）', '7', '合同'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'contract.expiry.escalate_high_days');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'pay.default_channel', '请款未指明渠道时记的支付渠道（pay_channel.channel_code；一期全部委托 nearpay）', 'NEARPAY', '支付'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'pay.default_channel');
