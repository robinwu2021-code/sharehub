-- ============================================================
-- V175 商家门户「商家资料」（需求方案 §10.3，G23；用户 2026-10-28：术语只用商家、站点）
--
-- 权限码：agent:profile:read / agent:profile:update，只作用于 /api/agent/me/**（商家号取自会话）。
-- 不复用 agent:agent:read —— agt_agent 没有数据范围规则，给商家那个码等于让它列出所有商家。
-- 菜单：商家门户「我的经营」下加一个叶子「商家资料」。新叶不存在才插（重复执行结果相同）。
-- **永远新开一个迁移，不要改本文件。**
-- ============================================================
SET NAMES utf8mb4;

INSERT INTO iam_permission (code, name, module) VALUES
  ('agent:profile:read', '商家资料 · 查看（本商家）', 'agent'),
  ('agent:profile:update', '商家资料 · 改联系人（本商家）', 'agent')
ON DUPLICATE KEY UPDATE name = VALUES(name), module = VALUES(module);

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT * FROM (
  SELECT 'M_my-biz__5' AS menu_no, 'M_my-biz' AS parent_no, '商家资料' AS name, 'Merchant Profile' AS name_en,
         'ملف التاجر' AS name_ar, 'ITEM' AS type, '/profile' AS path, NULL AS icon, '我的商家' AS group_name, 5 AS sort,
         'agent:profile:read' AS perm, 1 AS phase, 0 AS ready, NULL AS module, NULL AS modules, NULL AS match_paths,
         0 AS pin_bottom, NULL AS portal_for
) AS v
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = v.menu_no);
