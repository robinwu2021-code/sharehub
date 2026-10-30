-- ============================================================
-- ShareHub · 菜单：「代理商管理」→「商家管理」，按运营顺序重排（OM01）
--
-- 【为什么】
-- 2026-10-28 用户确认运营端菜单按运营顺序排列
-- （docs/requirements/运营端菜单清单-按运营顺序.md）。商家是第一阶段的结算单元（ADR-032），
-- 代理商 / 城市合伙人只是它的登记类型，所以一级菜单更名「商家管理」，三组跟着商家的一生走：
--   入驻与档案：入驻审核 · 商家档案 · 商家账号
--   分润与划拨：商家分润规则 · 设备/点位划拨
--   经营与退出：商家经营视图（新）· 商家绩效 · 退出与清算（新）
--
-- 【改了什么】
--   · 「代理收益结算」深链撤销（原 M_agent__6）：它指向财务的结算单，挂的 agent:settlement:read
--     没有任何后端端点挂载（功能权限清单 §缺口 2），BD 从这里点进去对真后端必 403。
--     结算单归「结算」模块，FINANCE 仍从财务管理进入。
--   · 「商家账号」的码由 agent:agent:update 改为 agent:account:manage，与 /api/agent/accounts
--     端点一致。两个码的持有角色都只有 BD（与 ADMIN），可见性不变。
--   · 分润规则与划拨两行对调位置（先定分润、再划资产）。
--
-- 【为什么定点更新而不是删了重插】
-- 菜单行的 path 不能在「菜单管理」里在线修改，所以旧编号 ↔ 页面的对应是确定的。
-- 按编号 UPDATE 能保留这些行的 id、created_at 与在线设置过的 visible；
-- 删了重插会把它们一起冲掉（V132 注释里说的那个代价）。
-- 名称、分组、排序是本次有意改的，覆盖在线改动是预期行为。
--
-- 【幂等】每条都带条件，重复执行不改变结果；新叶用 NOT EXISTS 插入。
-- **永远新开一个迁移，不要改本文件** —— 别人的库可能已经跑过它。
-- ============================================================
SET NAMES utf8mb4;

UPDATE iam_menu SET name = '商家管理', name_en = 'Merchants', name_ar = 'التجار'
 WHERE menu_no = 'M_agent';

-- 撤销结算深链（只认原路径，防止误删一条已被改作他用的行）
DELETE FROM iam_menu WHERE menu_no = 'M_agent__6' AND path = '/finance?tab=settlements';

-- 入驻与档案
UPDATE iam_menu SET group_name = '入驻与档案', sort = 1
 WHERE menu_no = 'M_agent__1';
UPDATE iam_menu SET name = '商家档案', name_en = 'Merchant Profiles', name_ar = 'ملفات التجار',
       group_name = '入驻与档案', sort = 2
 WHERE menu_no = 'M_agent__2';
UPDATE iam_menu SET name = '商家账号', name_en = 'Merchant Accounts', name_ar = 'حسابات التجار',
       perm = 'agent:account:manage', group_name = '入驻与档案', sort = 3
 WHERE menu_no = 'M_agent__3';

-- 分润与划拨：原 4 = 划拨、原 5 = 分润，对调。menu_no 唯一，借一个临时编号。
-- 条件里带 path，第二次执行时三条都不命中，不会来回翻转。
UPDATE iam_menu SET menu_no = 'M_agent__x4'
 WHERE menu_no = 'M_agent__4' AND path = '/agents?tab=assign';
UPDATE iam_menu SET menu_no = 'M_agent__4', name = '商家分润规则', name_en = 'Merchant Commission',
       name_ar = 'عمولة التجار', group_name = '分润与划拨', sort = 4
 WHERE menu_no = 'M_agent__5' AND path = '/agents?tab=commission';
UPDATE iam_menu SET menu_no = 'M_agent__5', group_name = '分润与划拨', sort = 5
 WHERE menu_no = 'M_agent__x4';

-- 经营与退出
UPDATE iam_menu SET name = '商家绩效', name_en = 'Merchant Performance', name_ar = 'أداء التجار',
       group_name = '经营与退出', sort = 7
 WHERE menu_no = 'M_agent__7';

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT 'M_agent__6', 'M_agent', '商家经营视图', 'Merchant Business', 'أعمال التجار', 'ITEM', '/agents?tab=business', NULL, '经营与退出', 6, 'agent:agent:read', 1, 1, NULL, NULL, NULL, 0, NULL
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = 'M_agent__6');

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT 'M_agent__8', 'M_agent', '退出与清算', 'Exit & Liquidation', 'الخروج والتصفية', 'ITEM', '/agents?tab=exits', NULL, '经营与退出', 8, 'agent:agent:update', 2, 1, NULL, NULL, NULL, 0, NULL
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = 'M_agent__8');
