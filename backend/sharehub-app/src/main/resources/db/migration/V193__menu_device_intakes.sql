-- 设备管理新增「入库与质检」组（矩阵 M1，用户 2026-10-29「按建议执行」），首个叶子「到仓待检」（V192 接口）。
-- 组排在设备档案与仓储与出库之间；到仓待检取 sort 3，原 3 ~ 18 顺延为 4 ~ 19（同 V165 / V190 做法）。
SET NAMES utf8mb4;

UPDATE iam_menu SET menu_no = CONCAT('M_tmp193_', id) WHERE parent_no = 'M_device' AND sort >= 3;
UPDATE iam_menu SET sort = sort + 1, menu_no = CONCAT('M_device__', sort + 1) WHERE menu_no LIKE 'M_tmp193_%';

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
VALUES ('M_device__3', 'M_device', '到仓待检', 'Intake Inspection', 'فحص الاستلام', 'ITEM', '/devices?tab=intakes', NULL, '入库与质检', 3,
        'device:inventory:read', 1, 1, NULL, NULL, NULL, 0, NULL);
