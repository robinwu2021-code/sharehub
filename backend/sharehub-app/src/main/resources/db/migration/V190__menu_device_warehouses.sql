-- 设备管理 › 仓储与出库 加「仓库」（只读；接口 GET /api/ops/inventory/warehouses 已于 V182 / S0 上线）。
-- 仓库排在库存余额之前，其后叶子顺延一位：先挪到临时号，再按新顺序重编（同 V165 的做法）。
SET NAMES utf8mb4;

UPDATE iam_menu SET menu_no = CONCAT('M_tmp190_', id) WHERE parent_no = 'M_device';

UPDATE iam_menu SET menu_no = 'M_device__1',  sort = 1  WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices';
UPDATE iam_menu SET menu_no = 'M_device__2',  sort = 2  WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=powerbanks';
UPDATE iam_menu SET menu_no = 'M_device__4',  sort = 4  WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=stock';
UPDATE iam_menu SET menu_no = 'M_device__5',  sort = 5  WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=inventory';
UPDATE iam_menu SET menu_no = 'M_device__6',  sort = 6  WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=issued';
UPDATE iam_menu SET menu_no = 'M_device__7',  sort = 7  WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=asset-diffs';
UPDATE iam_menu SET menu_no = 'M_device__8',  sort = 8  WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=installs';
UPDATE iam_menu SET menu_no = 'M_device__9',  sort = 9  WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=pending-live';
UPDATE iam_menu SET menu_no = 'M_device__10', sort = 10 WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=codes';
UPDATE iam_menu SET menu_no = 'M_device__11', sort = 11 WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=vendors';
UPDATE iam_menu SET menu_no = 'M_device__12', sort = 12 WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=monitor';
UPDATE iam_menu SET menu_no = 'M_device__13', sort = 13 WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=commands';
UPDATE iam_menu SET menu_no = 'M_device__14', sort = 14 WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=logs';
UPDATE iam_menu SET menu_no = 'M_device__15', sort = 15 WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=ota';
UPDATE iam_menu SET menu_no = 'M_device__16', sort = 16 WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=repairs';
UPDATE iam_menu SET menu_no = 'M_device__17', sort = 17 WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=removals';
UPDATE iam_menu SET menu_no = 'M_device__18', sort = 18 WHERE menu_no LIKE 'M_tmp190_%' AND path = '/devices?tab=retired';

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
VALUES ('M_device__3', 'M_device', '仓库', 'Warehouses', 'المستودعات', 'ITEM', '/devices?tab=warehouses', NULL, '仓储与出库', 3,
        'device:inventory:read', 1, 1, NULL, NULL, NULL, 0, NULL);
