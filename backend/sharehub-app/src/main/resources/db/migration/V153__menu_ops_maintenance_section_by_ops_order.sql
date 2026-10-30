-- ============================================================
-- ShareHub · 菜单：「运维管理」（OM06）—— 告警管理 + 客服管理 + 工单管理三合一
--
-- 【为什么】
-- 2026-10-28 用户确认运营端菜单按运营顺序排列
-- （docs/requirements/运营端菜单清单-按运营顺序.md）。三段是同一条处置链：
--   告警（机器发现问题）→ 客服（用户报来问题）→ 工单（派人去现场解决）
-- 来源：告警管理（M_alarm，整段并入后删除该 L1）· 客服管理（M_cs，同上）·
--       工单管理（M_workorder，保留该行并更名「运维管理」，落地页改为 /alarms）。
-- 客服原先的「退款/补偿」（/orders）「黑名单处理」（/users）两个快捷入口撤销：
-- 一个 URL 只属于一个 L1，从这里点进去面包屑与高亮跳到别的菜单。退款在销售管理 › 售后，
-- 黑名单在用户管理 › 风险，两处都在。
-- 工单组内巡检计划排到 SLA 管理之前：巡检计划生成工单，SLA 量的是所有工单。
--
-- 【做法】三段叶子先按 id 打散成临时编号，删掉两个快捷入口，再按 path 赋最终编号与分组
-- （重复执行结果相同）。按行更新保留 id、created_at 与在线设置过的 visible。
-- 删除 M_alarm、M_cs 后其后的 L1 排序前移（L1 的 sort 是 nav.ts 数组下标 + 1）。
-- **永远新开一个迁移，不要改本文件。**
-- ============================================================
SET NAMES utf8mb4;

UPDATE iam_menu SET name = '运维管理', name_en = 'Operations & Maintenance', name_ar = 'التشغيل والصيانة',
       path = '/alarms', match_paths = '["/alarms","/cs","/work-orders"]'
 WHERE menu_no = 'M_workorder';

-- 第一步：三段叶子打散
UPDATE iam_menu SET menu_no = CONCAT('M_tmp153_', id)
 WHERE parent_no IN ('M_alarm', 'M_cs', 'M_workorder');

-- 第二步：客服的两个跨段快捷入口撤销
DELETE FROM iam_menu WHERE menu_no LIKE 'M_tmp153_%' AND parent_no = 'M_cs' AND path IN ('/orders', '/users');

-- 第三步：按 path 赋最终编号
UPDATE iam_menu SET menu_no = 'M_workorder__1', parent_no = 'M_workorder', sort = 1, group_name = '告警'
 WHERE menu_no LIKE 'M_tmp153_%' AND path = '/alarms';
UPDATE iam_menu SET menu_no = 'M_workorder__2', parent_no = 'M_workorder', sort = 2, group_name = '告警'
 WHERE menu_no LIKE 'M_tmp153_%' AND path = '/alarms?tab=notices';
UPDATE iam_menu SET menu_no = 'M_workorder__3', parent_no = 'M_workorder', sort = 3, group_name = '告警'
 WHERE menu_no LIKE 'M_tmp153_%' AND path = '/alarms?tab=codes';
UPDATE iam_menu SET menu_no = 'M_workorder__4', parent_no = 'M_workorder', sort = 4, group_name = '告警'
 WHERE menu_no LIKE 'M_tmp153_%' AND path = '/alarms?tab=rules';
UPDATE iam_menu SET menu_no = 'M_workorder__5', parent_no = 'M_workorder', sort = 5, group_name = '客服'
 WHERE menu_no LIKE 'M_tmp153_%' AND path = '/cs';
UPDATE iam_menu SET menu_no = 'M_workorder__6', parent_no = 'M_workorder', sort = 6, group_name = '客服'
 WHERE menu_no LIKE 'M_tmp153_%' AND path = '/cs?tab=sessions';
UPDATE iam_menu SET menu_no = 'M_workorder__7', parent_no = 'M_workorder', sort = 7, group_name = '工单'
 WHERE menu_no LIKE 'M_tmp153_%' AND path = '/work-orders?view=list';
UPDATE iam_menu SET menu_no = 'M_workorder__8', parent_no = 'M_workorder', sort = 8, group_name = '工单'
 WHERE menu_no LIKE 'M_tmp153_%' AND path = '/work-orders?view=board';
UPDATE iam_menu SET menu_no = 'M_workorder__9', parent_no = 'M_workorder', sort = 9, group_name = '工单'
 WHERE menu_no LIKE 'M_tmp153_%' AND path = '/work-orders?view=inspection';
UPDATE iam_menu SET menu_no = 'M_workorder__10', parent_no = 'M_workorder', sort = 10, group_name = '工单'
 WHERE menu_no LIKE 'M_tmp153_%' AND path = '/work-orders?view=sla';

-- 告警管理、客服管理 L1 叶子已全部迁走或撤销，删除这两个 L1 行；其后 L1 排序前移
DELETE FROM iam_menu WHERE menu_no IN ('M_alarm', 'M_cs') AND parent_no IS NULL;
UPDATE iam_menu SET sort = 7 WHERE menu_no = 'M_workorder';
UPDATE iam_menu SET sort = 8 WHERE menu_no = 'M_venue';
UPDATE iam_menu SET sort = 9 WHERE menu_no = 'M_agent';
UPDATE iam_menu SET sort = 10 WHERE menu_no = 'M_order';
UPDATE iam_menu SET sort = 11 WHERE menu_no = 'M_finance';
UPDATE iam_menu SET sort = 12 WHERE menu_no = 'M_user';
UPDATE iam_menu SET sort = 13 WHERE menu_no = 'M_report';
UPDATE iam_menu SET sort = 14 WHERE menu_no = 'M_org';
UPDATE iam_menu SET sort = 15 WHERE menu_no = 'M_system';
UPDATE iam_menu SET sort = 16 WHERE menu_no = 'M_flow';
