-- ============================================================
-- V191 系统参数字典化（用户 2026-10-29：所有需要拍板的都做成配置 / 数据字典，将来管理员调整）
--
-- 此前 sys_param 只有 key / label / value：取值是自由文本，填错（"CEIL " / "0.6x"）时多数读取方静默回落缺省，
-- 界面显示「已保存」而实际没生效。本迁移给每个参数补「字典」元数据，服务端按它校验写入：
--   · value_type：TEXT / INT / DECIMAL / BOOL / ENUM
--   · value_options：ENUM 的可选值与中文含义（JSON 数组），界面渲染成下拉
--   · min_value / max_value：数值范围；pattern：TEXT 的格式（正则）
--   · nullable：允许留空（留空 = 关闭，如审批阈值）
--   · hint：改了会影响什么（只影响之后的订单 / 原文举例值）
--   · pending_decision：运营方待拍板项，系统设置页可一键筛出
-- 元数据只由迁移维护，界面只能改取值。本文件只用 MySQL 与 MariaDB 都认的语法。
-- ============================================================
ALTER TABLE sys_param
  ADD COLUMN value_type       VARCHAR(16)   NULL COMMENT 'TEXT/INT/DECIMAL/BOOL/ENUM；空 = TEXT',
  ADD COLUMN value_options    VARCHAR(1024) NULL COMMENT 'ENUM 可选值 JSON：[{"value":"CEIL","label":"…"}]',
  ADD COLUMN min_value        DECIMAL(18,4) NULL,
  ADD COLUMN max_value        DECIMAL(18,4) NULL,
  ADD COLUMN pattern          VARCHAR(128)  NULL COMMENT 'TEXT 取值的正则',
  ADD COLUMN nullable         TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '1 = 允许留空',
  ADD COLUMN hint             VARCHAR(256)  NULL COMMENT '改了影响什么',
  ADD COLUMN pending_decision TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1 = 运营方待拍板';

UPDATE sys_param SET value_type = 'ENUM', value_options = '[{"value": "CAP_AMOUNT", "label": "累计费用触顶即买断（现行）"}, {"value": "TIME_LIMIT", "label": "到最长时限发起最高收费"}]', min_value = NULL, max_value = NULL, pattern = NULL, nullable = 0, hint = 'K4：只影响之后到期的订单；最长时限在「计费默认」里配', pending_decision = 1 WHERE param_key = 'rent.overdue.mode';
UPDATE sys_param SET value_type = 'ENUM', value_options = '[{"value": "CEIL", "label": "不足一个周期按一个收（现行）"}, {"value": "FLOOR", "label": "不足一个周期不收"}, {"value": "HALF_UP", "label": "过半按一个收"}]', min_value = NULL, max_value = NULL, pattern = NULL, nullable = 0, hint = 'C03：新建计费方案的缺省；每个方案可单独改', pending_decision = 1 WHERE param_key = 'billing.unit_rounding';
UPDATE sys_param SET value_type = 'DECIMAL', value_options = NULL, min_value = '0', max_value = '100', pattern = NULL, nullable = 0, hint = 'C09：每单服务费（AED），0 = 不收；借出时定格，只影响之后的订单。原文举例 0.60 / 0.90', pending_decision = 1 WHERE param_key = 'rent.service_fee.amount';
UPDATE sys_param SET value_type = 'BOOL', value_options = NULL, min_value = NULL, max_value = NULL, pattern = NULL, nullable = 0, hint = 'C09：服务费是否计 VAT', pending_decision = 1 WHERE param_key = 'rent.service_fee.vat_applicable';
UPDATE sys_param SET value_type = 'BOOL', value_options = NULL, min_value = NULL, max_value = NULL, pattern = NULL, nullable = 0, hint = 'C09：服务费是否计入商家分成基数', pending_decision = 1 WHERE param_key = 'rent.service_fee.in_share_base';
UPDATE sys_param SET value_type = 'ENUM', value_options = '[{"value": "USER", "label": "用户付（加到应付）"}, {"value": "PLATFORM", "label": "平台承担（不进订单）"}]', min_value = NULL, max_value = NULL, pattern = NULL, nullable = 0, hint = 'C09：服务费由谁付', pending_decision = 1 WHERE param_key = 'rent.service_fee.payer';
UPDATE sys_param SET value_type = 'BOOL', value_options = NULL, min_value = NULL, max_value = NULL, pattern = NULL, nullable = 0, hint = 'C05 / K6：是否给 C 端每笔租赁自动开税务发票', pending_decision = 1 WHERE param_key = 'invoice.b2c.enabled';
UPDATE sys_param SET value_type = 'ENUM', value_options = '[{"value": "ON_CHARGE", "label": "每次收款即开"}, {"value": "ON_SETTLE", "label": "结单才开（已扣款未归还的等归还）"}]', min_value = NULL, max_value = NULL, pattern = NULL, nullable = 0, hint = 'C05：开票时点', pending_decision = 1 WHERE param_key = 'invoice.b2c.trigger';
UPDATE sys_param SET value_type = 'BOOL', value_options = NULL, min_value = NULL, max_value = NULL, pattern = NULL, nullable = 0, hint = '退款批准后自动开贷项通知单冲红', pending_decision = 0 WHERE param_key = 'invoice.credit_note.on_refund';
UPDATE sys_param SET value_type = 'TEXT', value_options = NULL, min_value = NULL, max_value = NULL, pattern = '^[A-Z]{2,6}$', nullable = 0, hint = '发票号前缀，2–6 位大写字母', pending_decision = 0 WHERE param_key = 'invoice.b2c.prefix';
UPDATE sys_param SET value_type = 'TEXT', value_options = NULL, min_value = NULL, max_value = NULL, pattern = '^[A-Z]{2}$', nullable = 0, hint = 'C01：开票主体取哪国的税务设置行（ISO 两位码）；主体本身在「税率与发票设置」里维护', pending_decision = 1 WHERE param_key = 'invoice.issuer_country';
UPDATE sys_param SET value_type = 'BOOL', value_options = NULL, min_value = NULL, max_value = NULL, pattern = NULL, nullable = 0, hint = 'C10：商家分成到账时是否代开自开票（只对有 VAT 税号的商家）', pending_decision = 1 WHERE param_key = 'finance.share.self_billing';
UPDATE sys_param SET value_type = 'TEXT', value_options = NULL, min_value = NULL, max_value = NULL, pattern = '^[A-Z]{2,6}$', nullable = 0, hint = '自开票单据号前缀', pending_decision = 0 WHERE param_key = 'finance.share.self_billing.prefix';
UPDATE sys_param SET value_type = 'ENUM', value_options = '[{"value": "PRE_TAX", "label": "税前"}, {"value": "GROSS", "label": "含税实收"}]', min_value = NULL, max_value = NULL, pattern = NULL, nullable = 0, hint = 'C07：分成基数口径；只影响之后的订单', pending_decision = 1 WHERE param_key = 'finance.share.base_mode';
UPDATE sys_param SET value_type = 'TEXT', value_options = NULL, min_value = NULL, max_value = NULL, pattern = '^[A-Z]{2}$', nullable = 0, hint = '分成折算税前时取哪国税率', pending_decision = 0 WHERE param_key = 'finance.share.tax_country';
UPDATE sys_param SET value_type = 'INT', value_options = NULL, min_value = '0', max_value = '4', pattern = NULL, nullable = 0, hint = 'C08：分润金额小数位', pending_decision = 1 WHERE param_key = 'finance.rounding.scale';
UPDATE sys_param SET value_type = 'ENUM', value_options = '[{"value": "DOWN", "label": "向下取整（现行，尾差归平台）"}, {"value": "HALF_UP", "label": "四舍五入"}]', min_value = NULL, max_value = NULL, pattern = NULL, nullable = 0, hint = 'C08：分润舍入', pending_decision = 1 WHERE param_key = 'finance.share.rounding';
UPDATE sys_param SET value_type = 'DECIMAL', value_options = NULL, min_value = '0', max_value = '1', pattern = NULL, nullable = 0, hint = 'C11：分成比例下限（0..1）；原文 0.15，0 = 不约束', pending_decision = 1 WHERE param_key = 'finance.share.rate_min';
UPDATE sys_param SET value_type = 'DECIMAL', value_options = NULL, min_value = '0', max_value = '1', pattern = NULL, nullable = 0, hint = 'C11：分成比例上限（0..1）；原文 0.50，1 = 不约束', pending_decision = 1 WHERE param_key = 'finance.share.rate_max';
UPDATE sys_param SET value_type = 'BOOL', value_options = NULL, min_value = NULL, max_value = NULL, pattern = NULL, nullable = 0, hint = 'C11：商家佣金里的单均固定额是否生成分润', pending_decision = 1 WHERE param_key = 'finance.share.fixed_enabled';
UPDATE sys_param SET value_type = 'DECIMAL', value_options = NULL, min_value = '0', max_value = '100000', pattern = NULL, nullable = 1, hint = 'SEC-03：客服补偿 / 免单 ≥ 此金额需财务审批；留空 = 不需要审批', pending_decision = 1 WHERE param_key = 'approval.compensation.threshold';
UPDATE sys_param SET value_type = 'DECIMAL', value_options = NULL, min_value = '0', max_value = '10000000', pattern = NULL, nullable = 1, hint = 'SEC-03：手工凭证借方合计 ≥ 此金额需另一位财务审批；留空 = 不需要审批', pending_decision = 1 WHERE param_key = 'approval.voucher.threshold';
UPDATE sys_param SET value_type = 'INT', value_options = NULL, min_value = '1', max_value = '365', pattern = NULL, nullable = 0, hint = 'C12：合同到期前多少天开始提醒；原文「2 个月」', pending_decision = 1 WHERE param_key = 'contract.expiry.remind_days';
UPDATE sys_param SET value_type = 'INT', value_options = NULL, min_value = '1', max_value = '365', pattern = NULL, nullable = 0, hint = 'C12：剩余多少天升为中优先级', pending_decision = 0 WHERE param_key = 'contract.expiry.escalate_medium_days';
UPDATE sys_param SET value_type = 'INT', value_options = NULL, min_value = '1', max_value = '365', pattern = NULL, nullable = 0, hint = 'C12：剩余多少天升为高优先级', pending_decision = 0 WHERE param_key = 'contract.expiry.escalate_high_days';
UPDATE sys_param SET value_type = 'TEXT', value_options = NULL, min_value = NULL, max_value = NULL, pattern = '^[A-Z0-9_]{2,32}$', nullable = 0, hint = '请款未指明渠道时记的支付渠道代码', pending_decision = 0 WHERE param_key = 'pay.default_channel';
