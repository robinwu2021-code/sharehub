-- ============================================================
-- ShareHub · 菜单：财务管理按运营顺序（OM07）—— 对账 → 账务 → 发票
--
-- 【为什么】
-- 2026-10-28 用户确认运营端菜单按运营顺序排列
-- （docs/requirements/运营端菜单清单-按运营顺序.md）。财务管理回答「钱对不对得上、欠谁多少」：
-- 先核对（对账），再记账与更正（账务分录 · 调整单），最后看应付（应付台账待后端，暂不建叶）。
--   对账：对账
--   账务：账务分录 · 调整单（新叶：原是「结算单」页签里的一个视图，升为本段页签 /finance?tab=adjustments）
--   发票：发票（待发票模块 OM09 调整时迁走）
-- 落地页由 /finance 改为 /finance?tab=reconcile。
--
-- 【做法】财务段三行按 id 打散后按 path 赋最终编号与分组，调整单新插（不存在才插）。
-- 按行更新保留 id、created_at 与在线设置过的 visible；重复执行结果相同。
-- **永远新开一个迁移，不要改本文件。**
-- ============================================================
SET NAMES utf8mb4;

UPDATE iam_menu SET path = '/finance?tab=reconcile' WHERE menu_no = 'M_finance';

UPDATE iam_menu SET menu_no = CONCAT('M_tmp156_', id) WHERE parent_no = 'M_finance';

UPDATE iam_menu SET menu_no = 'M_finance__1', sort = 1, group_name = '对账'
 WHERE menu_no LIKE 'M_tmp156_%' AND path = '/finance?tab=reconcile';
UPDATE iam_menu SET menu_no = 'M_finance__2', sort = 2, group_name = '账务'
 WHERE menu_no LIKE 'M_tmp156_%' AND path = '/finance?tab=ledger';
UPDATE iam_menu SET menu_no = 'M_finance__3', sort = 3, group_name = '账务'
 WHERE menu_no LIKE 'M_tmp156_%' AND path = '/finance?tab=adjustments';
UPDATE iam_menu SET menu_no = 'M_finance__4', sort = 4, group_name = '发票'
 WHERE menu_no LIKE 'M_tmp156_%' AND path = '/finance?tab=invoices';

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT * FROM (
  SELECT 'M_finance__3' AS menu_no, 'M_finance' AS parent_no, '调整单' AS name, 'Adjustments' AS name_en, 'قيود التسوية' AS name_ar,
         'ITEM' AS type, '/finance?tab=adjustments' AS path, NULL AS icon, '账务' AS group_name, 3 AS sort,
         'finance:settlement:read' AS perm, 1 AS phase, 0 AS ready, NULL AS module, NULL AS modules, NULL AS match_paths,
         0 AS pin_bottom, NULL AS portal_for
) AS v
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = v.menu_no);
