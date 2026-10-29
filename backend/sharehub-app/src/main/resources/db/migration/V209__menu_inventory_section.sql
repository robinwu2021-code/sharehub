-- ============================================================
-- ShareHub · 菜单：从「设备管理」拆出「库存管理」（L1）
--
-- 【为什么】用户 2026-10-29：库存管理作为独立模块，完整管理设备及其他物料（备件 / 配件、耗材、其他整机），
-- 将来可作为独立系统（承载方仍以 ai-shop 库存服务为准，见 docs/technical/独立进销存/）。
-- 菜单规范见 docs/technical/设计规范-库存管理菜单.md。
--   设备管理只留设备生命周期：设备档案 → 部署与安装 → 绑定上线 → 运行监控 → 维护与退出；
--   库存管理：主数据 → 出入库 → 库内作业 → 库存账 → 设置。
-- 一个 URL 只属于一个 L1：库存页签从 /devices 换到 /inventory；/devices 上的旧链接由前端转到 /inventory。
-- 「库存调拨」页签键由 inventory 改为 transfers（/inventory?tab=inventory 读起来是废话）。
-- 「到仓待检」更名「入库单」（V202 起它承载采购到货、期初建账、撤机回仓三类入库）。
-- 新增四叶：物料档案、物料单据、物料库存（后端未实现，ready = 0，真实后端模式下灰显）；库存设置（复用系统参数接口，ready = 1）。
-- 权限码不变（仍为 device:inventory:*）。
--
-- 【做法】新插 M_inventory（不存在才插），设备段打散后按 path 赋最终编号、所属段与分组（新旧 path 都认，重复执行结果相同）；
-- 按行更新保留 id、created_at 与在线设置过的 visible。L1 插在设备管理之后，其后 L1 排序后移一位。
-- **永远新开一个迁移，不要改本文件。**
-- ============================================================
SET NAMES utf8mb4;

UPDATE iam_menu SET sort = sort + 1
 WHERE parent_no IS NULL AND sort >= 6 AND menu_no <> 'M_inventory'
   AND NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = 'M_inventory');

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT * FROM (
  SELECT 'M_inventory' AS menu_no, NULL AS parent_no, '库存管理' AS name, 'Inventory' AS name_en, 'المخزون' AS name_ar,
         'MENU' AS type, '/inventory?tab=stock' AS path, 'Warehouse' AS icon, NULL AS group_name, 6 AS sort, NULL AS perm,
         1 AS phase, 0 AS ready, 'device' AS module, NULL AS modules,
         '["/inventory"]' AS match_paths, 0 AS pin_bottom, NULL AS portal_for
) AS v
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = v.menu_no);

-- 第一步：设备段与库存段打散
UPDATE iam_menu SET menu_no = CONCAT('M_tmp209_', id) WHERE parent_no IN ('M_device', 'M_inventory');

-- 第二步：库存段（按 path 认领，新旧 path 都认）
UPDATE iam_menu SET menu_no = 'M_inventory__2', parent_no = 'M_inventory', sort = 2, group_name = '主数据', path = '/inventory?tab=warehouses'
 WHERE menu_no LIKE 'M_tmp209_%' AND path IN ('/devices?tab=warehouses', '/inventory?tab=warehouses');
UPDATE iam_menu SET menu_no = 'M_inventory__3', parent_no = 'M_inventory', sort = 3, group_name = '主数据', path = '/inventory?tab=suppliers'
 WHERE menu_no LIKE 'M_tmp209_%' AND path IN ('/devices?tab=suppliers', '/inventory?tab=suppliers');
UPDATE iam_menu SET menu_no = 'M_inventory__4', parent_no = 'M_inventory', sort = 4, group_name = '出入库', path = '/inventory?tab=intakes',
       name = '入库单', name_en = 'Receipts', name_ar = 'إيصالات الاستلام'
 WHERE menu_no LIKE 'M_tmp209_%' AND path IN ('/devices?tab=intakes', '/inventory?tab=intakes');
UPDATE iam_menu SET menu_no = 'M_inventory__6', parent_no = 'M_inventory', sort = 6, group_name = '出入库', path = '/inventory?tab=issued'
 WHERE menu_no LIKE 'M_tmp209_%' AND path IN ('/devices?tab=issued', '/inventory?tab=issued');
UPDATE iam_menu SET menu_no = 'M_inventory__7', parent_no = 'M_inventory', sort = 7, group_name = '出入库', path = '/inventory?tab=disposals'
 WHERE menu_no LIKE 'M_tmp209_%' AND path IN ('/devices?tab=disposals', '/inventory?tab=disposals');
UPDATE iam_menu SET menu_no = 'M_inventory__8', parent_no = 'M_inventory', sort = 8, group_name = '库内作业', path = '/inventory?tab=transfers'
 WHERE menu_no LIKE 'M_tmp209_%' AND path IN ('/devices?tab=inventory', '/inventory?tab=transfers');
UPDATE iam_menu SET menu_no = 'M_inventory__9', parent_no = 'M_inventory', sort = 9, group_name = '库内作业', path = '/inventory?tab=counts'
 WHERE menu_no LIKE 'M_tmp209_%' AND path IN ('/devices?tab=counts', '/inventory?tab=counts');
UPDATE iam_menu SET menu_no = 'M_inventory__10', parent_no = 'M_inventory', sort = 10, group_name = '库内作业', path = '/inventory?tab=asset-diffs'
 WHERE menu_no LIKE 'M_tmp209_%' AND path IN ('/devices?tab=asset-diffs', '/inventory?tab=asset-diffs');
UPDATE iam_menu SET menu_no = 'M_inventory__11', parent_no = 'M_inventory', sort = 11, group_name = '库存账', path = '/inventory?tab=stock'
 WHERE menu_no LIKE 'M_tmp209_%' AND path IN ('/devices?tab=stock', '/inventory?tab=stock');
UPDATE iam_menu SET menu_no = 'M_inventory__13', parent_no = 'M_inventory', sort = 13, group_name = '库存账', path = '/inventory?tab=ledger'
 WHERE menu_no LIKE 'M_tmp209_%' AND path IN ('/devices?tab=ledger', '/inventory?tab=ledger');

-- 第三步：设备段剩下的按生命周期重编号
UPDATE iam_menu SET menu_no = 'M_device__1', sort = 1 WHERE menu_no LIKE 'M_tmp209_%' AND path = '/devices';
UPDATE iam_menu SET menu_no = 'M_device__2', sort = 2 WHERE menu_no LIKE 'M_tmp209_%' AND path = '/devices?tab=powerbanks';
UPDATE iam_menu SET menu_no = 'M_device__3', sort = 3 WHERE menu_no LIKE 'M_tmp209_%' AND path = '/devices?tab=installs';
UPDATE iam_menu SET menu_no = 'M_device__4', sort = 4 WHERE menu_no LIKE 'M_tmp209_%' AND path = '/devices?tab=pending-live';
UPDATE iam_menu SET menu_no = 'M_device__5', sort = 5 WHERE menu_no LIKE 'M_tmp209_%' AND path = '/devices?tab=codes';
UPDATE iam_menu SET menu_no = 'M_device__6', sort = 6 WHERE menu_no LIKE 'M_tmp209_%' AND path = '/devices?tab=vendors';
UPDATE iam_menu SET menu_no = 'M_device__7', sort = 7 WHERE menu_no LIKE 'M_tmp209_%' AND path = '/devices?tab=monitor';
UPDATE iam_menu SET menu_no = 'M_device__8', sort = 8 WHERE menu_no LIKE 'M_tmp209_%' AND path = '/devices?tab=commands';
UPDATE iam_menu SET menu_no = 'M_device__9', sort = 9 WHERE menu_no LIKE 'M_tmp209_%' AND path = '/devices?tab=logs';
UPDATE iam_menu SET menu_no = 'M_device__10', sort = 10 WHERE menu_no LIKE 'M_tmp209_%' AND path = '/devices?tab=ota';
UPDATE iam_menu SET menu_no = 'M_device__11', sort = 11 WHERE menu_no LIKE 'M_tmp209_%' AND path = '/devices?tab=repairs';
UPDATE iam_menu SET menu_no = 'M_device__12', sort = 12 WHERE menu_no LIKE 'M_tmp209_%' AND path = '/devices?tab=removals';
UPDATE iam_menu SET menu_no = 'M_device__13', sort = 13 WHERE menu_no LIKE 'M_tmp209_%' AND path = '/devices?tab=retired';

-- 第四步：库存段新增四叶（不存在才插）
INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT * FROM (
  SELECT 'M_inventory__1' AS menu_no, 'M_inventory' AS parent_no, '物料档案' AS name, 'Materials' AS name_en, 'المواد' AS name_ar, 'ITEM' AS type,
         '/inventory?tab=materials' AS path, NULL AS icon, '主数据' AS group_name, 1 AS sort, 'device:inventory:read' AS perm, 1 AS phase, 0 AS ready,
         NULL AS module, NULL AS modules, NULL AS match_paths, 0 AS pin_bottom, NULL AS portal_for
  UNION ALL SELECT 'M_inventory__5', 'M_inventory', '物料单据', 'Material Documents', 'مستندات المواد', 'ITEM',
         '/inventory?tab=material-docs', NULL, '出入库', 5, 'device:inventory:read', 1, 0, NULL, NULL, NULL, 0, NULL
  UNION ALL SELECT 'M_inventory__12', 'M_inventory', '物料库存', 'Material Stock', 'مخزون المواد', 'ITEM',
         '/inventory?tab=material-stock', NULL, '库存账', 12, 'device:inventory:read', 1, 0, NULL, NULL, NULL, 0, NULL
  UNION ALL SELECT 'M_inventory__14', 'M_inventory', '库存设置', 'Inventory Settings', 'إعدادات المخزون', 'ITEM',
         '/inventory?tab=settings', NULL, '设置', 14, 'device:inventory:read', 1, 1, NULL, NULL, NULL, 0, NULL
) AS v
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = v.menu_no);
