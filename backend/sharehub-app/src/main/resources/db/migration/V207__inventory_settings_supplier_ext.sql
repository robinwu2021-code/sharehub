-- 型号与库存设置、供应商延伸（库存缺口 P2 #7）。
--   1) 库存参数补齐字典元数据并入「库存」组（仓库页「库存设置」统一维护）：
--      inv.intake.overdue_days / inv.removal.return_grace_minutes 此前只有代码默认值，这里种入便于改；
--      新增 inv.qc.powerbank_policy：FULL 逐块质检（默认）/ SKIP 充电宝免检 —— 采购到货的宝登记即入库（放行凭证 NOQC-{设备号}），柜仍须逐台质检。
--      跟踪方式不设参数：柜与宝都是逐件（序列号）跟踪，没有按数量管的物料。
--   2) 供应商关联设备厂商（厂商接入 vendor_code，可空）。
SET NAMES utf8mb4;

INSERT INTO sys_param (tenant_id, param_key, label, value, group_name, value_type, value_options, min_value, max_value, nullable, hint)
SELECT 'MAIN', 'inv.intake.overdue_days', '到仓待检超期天数', '7', '库存', 'INT', NULL, 1, 90, 0,
       '到达超过这么多天还没处理完的待检单，给仓管发待办（告警 INTAKE_OVERDUE）'
WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'inv.intake.overdue_days');

INSERT INTO sys_param (tenant_id, param_key, label, value, group_name, value_type, value_options, min_value, max_value, nullable, hint)
SELECT 'MAIN', 'inv.removal.return_grace_minutes', '撤机未回仓宽限（分钟）', '30', '库存', 'INT', NULL, 1, 1440, 0,
       '撤机完工去向回仓，超过这么久还没有回仓调拨单就给运营发待办（告警 REMOVAL_RETURN_MISSING）'
WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'inv.removal.return_grace_minutes');

INSERT INTO sys_param (tenant_id, param_key, label, value, group_name, value_type, value_options, nullable, hint)
SELECT 'MAIN', 'inv.qc.powerbank_policy', '充电宝质检策略', 'FULL', '库存', 'ENUM',
       '[{"value": "FULL", "label": "逐块质检"}, {"value": "SKIP", "label": "免检（登记即入库）"}]', 0,
       '只影响采购到货的充电宝；机柜始终逐台质检，撤机回仓始终复检'
WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'inv.qc.powerbank_policy');

UPDATE sys_param SET group_name = '库存' WHERE param_key LIKE 'inv.%' AND (group_name IS NULL OR group_name <> '库存');

ALTER TABLE inv_supplier ADD COLUMN vendor_code VARCHAR(32) NULL COMMENT '对应的设备厂商（厂商接入），可空' AFTER name;
