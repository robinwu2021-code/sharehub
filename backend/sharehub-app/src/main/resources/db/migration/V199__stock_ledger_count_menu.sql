-- S4 库存流水与盘点：设备管理 › 仓储与出库 加「库存流水」「盘点」两个叶子（排在库存余额之后），
-- 并把盘点执行码 device:inventory:stocktake 登记进权限目录 —— 此前只声明未强制（POST /api/ops/inventory/counts* 起强制）。
-- 设备叶子整体按 sort 重编号（先改临时名防撞，同 V190 / V193 做法）。
SET NAMES utf8mb4;

INSERT IGNORE INTO iam_permission (code, name, module) VALUES
  ('device:inventory:stocktake', '盘点 执行', 'device');

UPDATE iam_menu SET menu_no = CONCAT('M_tmp199_', id) WHERE parent_no = 'M_device';
UPDATE iam_menu SET sort = sort + 2 WHERE menu_no LIKE 'M_tmp199_%' AND sort >= 6;
UPDATE iam_menu SET menu_no = CONCAT('M_device__', sort) WHERE menu_no LIKE 'M_tmp199_%';

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
VALUES ('M_device__6', 'M_device', '库存流水', 'Stock Ledger', 'سجل المخزون', 'ITEM', '/devices?tab=ledger', NULL, '仓储与出库', 6,
        'device:inventory:read', 1, 1, NULL, NULL, NULL, 0, NULL),
       ('M_device__7', 'M_device', '盘点', 'Stock Count', 'الجرد', 'ITEM', '/devices?tab=counts', NULL, '仓储与出库', 7,
        'device:inventory:read', 1, 1, NULL, NULL, NULL, 0, NULL);
