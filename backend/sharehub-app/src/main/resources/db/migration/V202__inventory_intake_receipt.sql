-- 入库单（库存缺口 P0 #1，用户 2026-10-29「按以上顺序执行」）：在到仓待检单上补齐入库单的要素。
--   kind 加 OPENING 期初建账：设备早已在仓、历史上已验收，登记即入库，不走质检（放行凭证记 OPEN-{设备号}，
--     与存量迁移的 MIG-{设备号} 同口径）；ARRIVAL 采购到货 / RETURN 撤机回仓不变。
--   明细加 unit_cost、单头加 currency：采购对账与库存金额报表的取数来源；可空（撤机回仓无成本）。
SET NAMES utf8mb4;

ALTER TABLE inv_intake MODIFY COLUMN kind VARCHAR(16) NOT NULL
  COMMENT 'ARRIVAL 采购到货 / RETURN 撤机回仓 / OPENING 期初建账';
ALTER TABLE inv_intake ADD COLUMN currency VARCHAR(8) NULL COMMENT '单价币种（ISO 4217，如 AED）' AFTER supplier_name;
ALTER TABLE inv_intake_item ADD COLUMN unit_cost DECIMAL(12,2) NULL COMMENT '单价（币种见单头 currency）' AFTER item_no;
