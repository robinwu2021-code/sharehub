-- ============================================================
-- ShareHub · 菜单：设备管理补五个生命周期视图（设备生命周期管理设计 §五、§六 A2 ~ A4）
--
-- 【为什么】设备管理按生命周期排（V163）后，装机、待上线、维修、撤机、报废这几步在菜单里仍找不到：
-- 装机 / 撤机单混在运维工单里，门禁与试借还藏在设备详情，维修、撤出、报废只是台账上的状态值。
-- 五个视图都是既有数据的设备视角，不新增后端端点：
--   部署与安装：部署与装机（装机工单）
--   绑定上线：待上线（已绑定未上线 + 上线门禁）· 设备编码 · 厂商接入
--   维护与退出：故障与维修 · 撤机与回仓 · 报废与遗失
-- 【做法】设备段叶子按 id 打散后按 path 赋最终编号与分组；新叶不存在才插（重复执行结果相同）。
-- **永远新开一个迁移，不要改本文件。**
-- ============================================================
SET NAMES utf8mb4;

UPDATE iam_menu SET menu_no = CONCAT('M_tmp165_', id) WHERE parent_no = 'M_device';

UPDATE iam_menu SET menu_no = 'M_device__1',  sort = 1,  group_name = '设备档案'   WHERE menu_no LIKE 'M_tmp165_%' AND path = '/devices';
UPDATE iam_menu SET menu_no = 'M_device__2',  sort = 2,  group_name = '设备档案'   WHERE menu_no LIKE 'M_tmp165_%' AND path = '/devices?tab=powerbanks';
UPDATE iam_menu SET menu_no = 'M_device__3',  sort = 3,  group_name = '仓储与出库' WHERE menu_no LIKE 'M_tmp165_%' AND path = '/devices?tab=stock';
UPDATE iam_menu SET menu_no = 'M_device__4',  sort = 4,  group_name = '仓储与出库' WHERE menu_no LIKE 'M_tmp165_%' AND path = '/devices?tab=inventory';
UPDATE iam_menu SET menu_no = 'M_device__5',  sort = 5,  group_name = '仓储与出库' WHERE menu_no LIKE 'M_tmp165_%' AND path = '/devices?tab=issued';
UPDATE iam_menu SET menu_no = 'M_device__6',  sort = 6,  group_name = '仓储与出库' WHERE menu_no LIKE 'M_tmp165_%' AND path = '/devices?tab=asset-diffs';
UPDATE iam_menu SET menu_no = 'M_device__9',  sort = 9,  group_name = '绑定上线'   WHERE menu_no LIKE 'M_tmp165_%' AND path = '/devices?tab=codes';
UPDATE iam_menu SET menu_no = 'M_device__10', sort = 10, group_name = '绑定上线'   WHERE menu_no LIKE 'M_tmp165_%' AND path = '/devices?tab=vendors';
UPDATE iam_menu SET menu_no = 'M_device__11', sort = 11, group_name = '运行监控'   WHERE menu_no LIKE 'M_tmp165_%' AND path = '/devices?tab=monitor';
UPDATE iam_menu SET menu_no = 'M_device__12', sort = 12, group_name = '运行监控'   WHERE menu_no LIKE 'M_tmp165_%' AND path = '/devices?tab=commands';
UPDATE iam_menu SET menu_no = 'M_device__13', sort = 13, group_name = '运行监控'   WHERE menu_no LIKE 'M_tmp165_%' AND path = '/devices?tab=logs';
UPDATE iam_menu SET menu_no = 'M_device__14', sort = 14, group_name = '运行监控'   WHERE menu_no LIKE 'M_tmp165_%' AND path = '/devices?tab=ota';

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT * FROM (
  SELECT 'M_device__7' AS menu_no, 'M_device' AS parent_no, '部署与装机' AS name, 'Installations' AS name_en, 'عمليات التركيب' AS name_ar,
         'ITEM' AS type, '/devices?tab=installs' AS path, NULL AS icon, '部署与安装' AS group_name, 7 AS sort,
         'workorder:wo:read' AS perm, 1 AS phase, 0 AS ready, NULL AS module, NULL AS modules, NULL AS match_paths,
         0 AS pin_bottom, NULL AS portal_for
  UNION ALL SELECT 'M_device__8', 'M_device', '待上线', 'Pending Go-live', 'بانتظار التشغيل', 'ITEM', '/devices?tab=pending-live', NULL, '绑定上线', 8, 'device:cabinet:read', 1, 0, NULL, NULL, NULL, 0, NULL
  UNION ALL SELECT 'M_device__15', 'M_device', '故障与维修', 'Faults & Repairs', 'الأعطال والإصلاح', 'ITEM', '/devices?tab=repairs', NULL, '维护与退出', 15, 'device:cabinet:read', 1, 0, NULL, NULL, NULL, 0, NULL
  UNION ALL SELECT 'M_device__16', 'M_device', '撤机与回仓', 'Removals & Returns', 'الإزالة والإرجاع', 'ITEM', '/devices?tab=removals', NULL, '维护与退出', 16, 'workorder:wo:read', 1, 0, NULL, NULL, NULL, 0, NULL
  UNION ALL SELECT 'M_device__17', 'M_device', '报废与遗失', 'Scrapped & Lost', 'المُتلَف والمفقود', 'ITEM', '/devices?tab=retired', NULL, '维护与退出', 17, 'device:cabinet:read', 1, 0, NULL, NULL, NULL, 0, NULL
) AS v
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = v.menu_no);
