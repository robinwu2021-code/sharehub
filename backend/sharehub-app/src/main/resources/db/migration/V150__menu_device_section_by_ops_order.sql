-- ============================================================
-- ShareHub · 菜单：设备管理按运营顺序重排（OM03）+ 供应商接入从系统设置搬入
--
-- 【为什么】
-- 2026-10-28 用户确认运营端菜单按运营顺序排列
-- （docs/requirements/运营端菜单清单-按运营顺序.md）。设备管理承接 P4 布放：
--   资产台账：设备台账 · 充电宝管理 · 设备编码
--   库存与流转：库存余额（新）· 库存调拨 · 资产差异（新，原为库存调拨的子视图）
--   设备接入：供应商接入（自系统设置搬来 —— 新厂商的设备要先接入才能上线）
--   在线运行：实时监控 · 远程控制与指令记录 · 设备日志 · 固件 OTA
-- 「采购与入库」组待后端补供应商 / 采购单 / 收货实体后再加。
--
-- 【做法】同 V149：两段叶子先按 id 打散成临时编号，再按 path 赋最终编号、所属段与分组；
-- 新叶用 NOT EXISTS 插入。按行更新保留 id、created_at 与在线设置过的 visible。
-- 分期不改：库存余额一期且已就绪；资产差异沿用库存调拨的二期（拆出来不改分期）。
-- **永远新开一个迁移，不要改本文件。**
-- ============================================================
SET NAMES utf8mb4;

UPDATE iam_menu SET path = '/system?tab=payment' WHERE menu_no = 'M_system';

-- 第一步：两段叶子打散
UPDATE iam_menu SET menu_no = CONCAT('M_tmp150_', id)
 WHERE parent_no IN ('M_device', 'M_system');

-- 第二步：设备管理
UPDATE iam_menu SET menu_no = 'M_device__1', parent_no = 'M_device', group_name = '资产台账', sort = 1
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/devices';
UPDATE iam_menu SET menu_no = 'M_device__2', parent_no = 'M_device', group_name = '资产台账', sort = 2
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/devices?tab=powerbanks';
UPDATE iam_menu SET menu_no = 'M_device__3', parent_no = 'M_device', group_name = '资产台账', sort = 3
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/devices?tab=codes';
UPDATE iam_menu SET menu_no = 'M_device__5', parent_no = 'M_device', group_name = '库存与流转', sort = 5
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/devices?tab=inventory';
-- 新旧 path 都认：重复执行时它已经是 /devices?tab=vendors，只认旧 path 会让它停在临时编号上
UPDATE iam_menu SET menu_no = 'M_device__7', parent_no = 'M_device', group_name = '设备接入', sort = 7,
       path = '/devices?tab=vendors'
 WHERE menu_no LIKE 'M_tmp150_%' AND path IN ('/system?tab=vendors', '/devices?tab=vendors');
UPDATE iam_menu SET menu_no = 'M_device__8', parent_no = 'M_device', group_name = '在线运行', sort = 8
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/devices?tab=monitor';
UPDATE iam_menu SET menu_no = 'M_device__9', parent_no = 'M_device', group_name = '在线运行', sort = 9,
       name = '远程控制与指令记录'
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/devices?tab=commands';
UPDATE iam_menu SET menu_no = 'M_device__10', parent_no = 'M_device', group_name = '在线运行', sort = 10
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/devices?tab=logs';
UPDATE iam_menu SET menu_no = 'M_device__11', parent_no = 'M_device', group_name = '在线运行', sort = 11
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/devices?tab=ota';

-- 第二步：系统设置（去掉供应商接入后前移一位，分组不变）
UPDATE iam_menu SET menu_no = 'M_system__1', parent_no = 'M_system', sort = 1
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/system?tab=payment';
UPDATE iam_menu SET menu_no = 'M_system__2', parent_no = 'M_system', sort = 2
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/system?tab=notify';
UPDATE iam_menu SET menu_no = 'M_system__3', parent_no = 'M_system', sort = 3
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/system?tab=notify-log';
UPDATE iam_menu SET menu_no = 'M_system__4', parent_no = 'M_system', sort = 4
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/system?tab=notify-blacklist';
UPDATE iam_menu SET menu_no = 'M_system__5', parent_no = 'M_system', sort = 5
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/system?tab=rules';
UPDATE iam_menu SET menu_no = 'M_system__6', parent_no = 'M_system', sort = 6
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/system?tab=login';
UPDATE iam_menu SET menu_no = 'M_system__7', parent_no = 'M_system', sort = 7
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/system?tab=dict';
UPDATE iam_menu SET menu_no = 'M_system__8', parent_no = 'M_system', sort = 8
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/system?tab=region';
UPDATE iam_menu SET menu_no = 'M_system__9', parent_no = 'M_system', sort = 9
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/system?tab=params';
UPDATE iam_menu SET menu_no = 'M_system__10', parent_no = 'M_system', sort = 10
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/system?tab=tax';
UPDATE iam_menu SET menu_no = 'M_system__11', parent_no = 'M_system', sort = 11
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/system?tab=markets';
UPDATE iam_menu SET menu_no = 'M_system__12', parent_no = 'M_system', sort = 12
 WHERE menu_no LIKE 'M_tmp150_%' AND path = '/system?tab=openapi';

-- 新叶：库存余额、资产差异
INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT 'M_device__4', 'M_device', '库存余额', 'Stock Balance', 'رصيد المخزون', 'ITEM', '/devices?tab=stock', NULL, '库存与流转', 4, 'device:inventory:read', 1, 1, NULL, NULL, NULL, 0, NULL
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = 'M_device__4');

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT 'M_device__6', 'M_device', '资产差异', 'Asset Discrepancies', 'فروقات الأصول', 'ITEM', '/devices?tab=asset-diffs', NULL, '库存与流转', 6, 'device:inventory:read', 2, 0, NULL, NULL, NULL, 0, NULL
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = 'M_device__6');
