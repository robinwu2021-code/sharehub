-- ============================================================
-- ShareHub · 菜单：设备管理按生命周期重排 + 出库池；商家门户加「我的站点」
--
-- 【为什么】
-- 2026-10-28 用户裁决（docs/technical/设备绑定与商家归属-现状分析.md §五、设备出库与绑定-开发计划.md）：
--   绑定商家先出库，出库必须选商家；绑定的设备只从该商家的出库池里选，默认绑到默认站点、默认点位；
--   站点就是门店，商家端叫「我的站点」；站点与点位一期由运营代建，绑定在运营端做。
-- 设备管理的分组改为生命周期（docs/requirements/设备生命周期管理-运营端设计.md §五）：
--   设备档案：设备台账 · 充电宝管理
--   仓储与出库：库存余额 · 库存调拨 · 出库池（新）· 资产差异
--   绑定上线：设备编码 · 厂商接入（原「供应商接入」更名，「供应商」留给采购）
--   运行监控：实时监控 · 远程控制与指令记录 · 设备日志 · 固件 OTA
-- 商家门户「我的资产」加「我的站点」（复用 /operation/sites，按数据范围只看自己的），并认领三条路径。
--
-- 【做法】两段叶子按 id 打散后按 path 赋最终编号与分组（重复执行结果相同）；新叶不存在才插。
-- 按行更新保留 id、created_at 与在线设置过的 visible。**永远新开一个迁移，不要改本文件。**
-- ============================================================
SET NAMES utf8mb4;

UPDATE iam_menu SET menu_no = CONCAT('M_tmp163_', id) WHERE parent_no IN ('M_device', 'M_my-asset');

UPDATE iam_menu SET menu_no = 'M_device__1', sort = 1, group_name = '设备档案' WHERE menu_no LIKE 'M_tmp163_%' AND path = '/devices' AND parent_no = 'M_device';
UPDATE iam_menu SET menu_no = 'M_device__2', sort = 2, group_name = '设备档案' WHERE menu_no LIKE 'M_tmp163_%' AND path = '/devices?tab=powerbanks';
UPDATE iam_menu SET menu_no = 'M_device__3', sort = 3, group_name = '仓储与出库' WHERE menu_no LIKE 'M_tmp163_%' AND path = '/devices?tab=stock';
UPDATE iam_menu SET menu_no = 'M_device__4', sort = 4, group_name = '仓储与出库' WHERE menu_no LIKE 'M_tmp163_%' AND path = '/devices?tab=inventory';
UPDATE iam_menu SET menu_no = 'M_device__6', sort = 6, group_name = '仓储与出库' WHERE menu_no LIKE 'M_tmp163_%' AND path = '/devices?tab=asset-diffs';
UPDATE iam_menu SET menu_no = 'M_device__7', sort = 7, group_name = '绑定上线' WHERE menu_no LIKE 'M_tmp163_%' AND path = '/devices?tab=codes';
UPDATE iam_menu SET menu_no = 'M_device__8', sort = 8, group_name = '绑定上线',
       name = '厂商接入', name_en = 'Vendor Integration', name_ar = 'تكامل المصنّعين'
 WHERE menu_no LIKE 'M_tmp163_%' AND path = '/devices?tab=vendors';
UPDATE iam_menu SET menu_no = 'M_device__9', sort = 9, group_name = '运行监控' WHERE menu_no LIKE 'M_tmp163_%' AND path = '/devices?tab=monitor';
UPDATE iam_menu SET menu_no = 'M_device__10', sort = 10, group_name = '运行监控' WHERE menu_no LIKE 'M_tmp163_%' AND path = '/devices?tab=commands';
UPDATE iam_menu SET menu_no = 'M_device__11', sort = 11, group_name = '运行监控' WHERE menu_no LIKE 'M_tmp163_%' AND path = '/devices?tab=logs';
UPDATE iam_menu SET menu_no = 'M_device__12', sort = 12, group_name = '运行监控' WHERE menu_no LIKE 'M_tmp163_%' AND path = '/devices?tab=ota';

UPDATE iam_menu SET menu_no = 'M_my-asset__2', sort = 2 WHERE menu_no LIKE 'M_tmp163_%' AND parent_no = 'M_my-asset' AND path = '/devices';
UPDATE iam_menu SET menu_no = 'M_my-asset__3', sort = 3 WHERE menu_no LIKE 'M_tmp163_%' AND parent_no = 'M_my-asset' AND path = '/orders';
UPDATE iam_menu SET match_paths = '["/devices","/operation/sites","/orders"]' WHERE menu_no = 'M_my-asset';

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT * FROM (
  SELECT 'M_device__5' AS menu_no, 'M_device' AS parent_no, '出库池' AS name, 'Outbound Pool' AS name_en, 'مجمع الإخراج' AS name_ar,
         'ITEM' AS type, '/devices?tab=issued' AS path, NULL AS icon, '仓储与出库' AS group_name, 5 AS sort,
         'device:inventory:read' AS perm, 1 AS phase, 0 AS ready, NULL AS module, NULL AS modules, NULL AS match_paths,
         0 AS pin_bottom, NULL AS portal_for
  UNION ALL
  SELECT 'M_my-asset__1', 'M_my-asset', '我的站点', 'My Sites', 'مواقعي',
         'ITEM', '/operation/sites', NULL, '设备与订单', 1,
         'location:poi:read', 1, 0, NULL, NULL, NULL, 0, NULL
) AS v
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = v.menu_no);
