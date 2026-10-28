-- 商家一期（需求方案 D9 / D10 / D2，优化方案 B1）：
--   ① 分成基数改为税前，且口径可配置；每笔分润明细快照当时的口径、税率与基数
--   ② 场地方一期不参与分成（开关，默认关）
--   ③ 一期只走运营代建，商家自助注册关闭（开关，默认关）
--
-- 写法说明：本文件**不用** `ADD COLUMN IF NOT EXISTS` —— 那是 MariaDB 方言，本机 MySQL 9.7 不认。
-- 普通 ADD COLUMN 两边都能跑；Flyway 保证每个版本只执行一次，不需要幂等写法。

-- ── ① 分润明细：基数快照 ──────────────────────────────────────────
-- 原有 gross_amount（本单实收，含税）保留不动；新列为空表示本迁移之前按旧口径（含税实收）生成。
ALTER TABLE share_record
  ADD COLUMN base_mode   VARCHAR(16)   NULL COMMENT '分成基数口径词表：PRE_TAX/GROSS（PRE_TAX=实收折算税前；GROSS=含税实收）；空=本迁移前生成，按含税实收' AFTER gross_amount,
  ADD COLUMN tax_rate    DECIMAL(5,2)  NULL COMMENT '折算所用税率，百分数口径（5.00=5%）；GROSS 口径或价外税时为 0' AFTER base_mode,
  ADD COLUMN base_amount DECIMAL(18,2) NULL COMMENT '分成基数（amount = base_amount × rate 向下取整到分）' AFTER tax_rate;

-- ── 系统参数 ──────────────────────────────────────────────────────
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name) VALUES
  ('MAIN', 'finance.share.base_mode',     '分成基数口径：PRE_TAX 税前 / GROSS 含税实收（只影响之后的订单）', 'PRE_TAX', '财务'),
  ('MAIN', 'finance.share.tax_country',   '分成折算税前时取哪个国家的税率（对应「税率与发票设置」的国家代码）', 'AE', '财务'),
  ('MAIN', 'finance.share.venue_enabled', '场地方是否参与分成与结算：1 是 / 0 否（一期为 0）', '0', '财务'),
  ('MAIN', 'agent.apply.self_service_enabled', '商家自助注册入口：1 开 / 0 关（一期只走运营代建）', '0', '商家')
ON DUPLICATE KEY UPDATE label = VALUES(label), group_name = VALUES(group_name);

-- ── 税率：阿联酋 VAT 5%，含税价 ────────────────────────────────────
-- 「税率与发票设置」此前没有任何行，折算税前无处取数。已有 AE 行时不覆盖（运营可能已改过）。
INSERT IGNORE INTO sys_tax_setting (tenant_id, country, country_name, tax_name, rate_percent, included_in_price)
VALUES ('MAIN', 'AE', 'United Arab Emirates', 'VAT', 5.00, 1);
