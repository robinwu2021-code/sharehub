-- ============================================================
-- ShareHub · 菜单：从「财务管理」拆出「结算」（OM08）—— 分润 → 结算 → 付款
--
-- 【为什么】
-- 2026-10-28 用户确认运营端菜单按运营顺序排列
-- （docs/requirements/运营端菜单清单-按运营顺序.md）。结算回答「该分给商家多少、付了没有」，
-- 排在财务管理之前（应付台账与调整单都消费结算的产出）。
--   分润：分润规则 · 分润明细 · 分润统计 · 站点分成 · 分成方分成（后两项自运营管理迁来）
--   结算：结算单
--   付款：提现审核 · 收款账户
-- 结算的六个页签从 /finance 换到 /settlement：一个 URL 只属于一个 L1（菜单按路径前缀定归属），
-- /finance 只留账务分录、对账、发票。/finance 上的旧链接由前端转到 /settlement。
-- 财务管理的「代理分润配置」深链（/agents?tab=commission）撤销：它是跨 L1 深链，
-- 商家管理 › 商家分润规则本身就在。
-- 商家门户「我的收益 / 我的结算 / 申请提现」三项 path 同步换到 /settlement。
--
-- 【做法】新插 M_settlement（不存在才插）；财务段与运营管理两项分成先按 id 打散，删深链，
-- 再按 path 赋最终编号、所属段与分组（新旧 path 都认，重复执行结果相同）。
-- 按行更新保留 id、created_at 与在线设置过的 visible。
-- 插在财务管理之前，其后 L1 排序后移一位（L1 的 sort 是 nav.ts 数组下标 + 1）。
-- **永远新开一个迁移，不要改本文件。**
-- ============================================================
SET NAMES utf8mb4;

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT * FROM (
  SELECT 'M_settlement' AS menu_no, NULL AS parent_no, '结算' AS name, 'Settlement' AS name_en, 'التسوية' AS name_ar,
         'MENU' AS type, '/settlement?tab=settlements' AS path, 'HandCoins' AS icon, NULL AS group_name, 11 AS sort, NULL AS perm,
         1 AS phase, 0 AS ready, 'finance' AS module, NULL AS modules,
         '["/settlement","/operation/site-sharing","/operation/payee-sharing"]' AS match_paths, 0 AS pin_bottom, NULL AS portal_for
) AS v
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = v.menu_no);

-- 商家门户三项换路径（编号不变）
UPDATE iam_menu SET path = '/settlement?tab=records'
 WHERE parent_no = 'M_my-biz' AND path IN ('/finance?tab=records', '/settlement?tab=records');
UPDATE iam_menu SET path = '/settlement?tab=settlements'
 WHERE parent_no = 'M_my-biz' AND path IN ('/finance?tab=settlements', '/settlement?tab=settlements');
UPDATE iam_menu SET path = '/settlement?tab=withdrawals'
 WHERE parent_no = 'M_my-biz' AND path IN ('/finance?tab=withdrawals', '/settlement?tab=withdrawals');

-- 第一步：财务段、结算段与运营管理的两项分成打散
UPDATE iam_menu SET menu_no = CONCAT('M_tmp154_', id)
 WHERE parent_no IN ('M_finance', 'M_settlement')
    OR (parent_no = 'M_operation' AND path IN ('/operation/site-sharing', '/operation/payee-sharing'));

-- 第二步：撤「代理分润配置」跨 L1 深链
DELETE FROM iam_menu WHERE menu_no LIKE 'M_tmp154_%' AND parent_no = 'M_finance' AND path = '/agents?tab=commission';

-- 第三步：按 path 赋最终编号
UPDATE iam_menu SET menu_no = 'M_settlement__1', parent_no = 'M_settlement', sort = 1, group_name = '分润', path = '/settlement?tab=rules'
 WHERE menu_no LIKE 'M_tmp154_%' AND path IN ('/finance?tab=rules', '/settlement?tab=rules');
UPDATE iam_menu SET menu_no = 'M_settlement__2', parent_no = 'M_settlement', sort = 2, group_name = '分润', path = '/settlement?tab=records'
 WHERE menu_no LIKE 'M_tmp154_%' AND path IN ('/finance?tab=records', '/settlement?tab=records');
UPDATE iam_menu SET menu_no = 'M_settlement__3', parent_no = 'M_settlement', sort = 3, group_name = '分润', path = '/settlement?tab=summary'
 WHERE menu_no LIKE 'M_tmp154_%' AND path IN ('/finance?tab=summary', '/settlement?tab=summary');
UPDATE iam_menu SET menu_no = 'M_settlement__4', parent_no = 'M_settlement', sort = 4, group_name = '分润'
 WHERE menu_no LIKE 'M_tmp154_%' AND path = '/operation/site-sharing';
UPDATE iam_menu SET menu_no = 'M_settlement__5', parent_no = 'M_settlement', sort = 5, group_name = '分润'
 WHERE menu_no LIKE 'M_tmp154_%' AND path = '/operation/payee-sharing';
UPDATE iam_menu SET menu_no = 'M_settlement__6', parent_no = 'M_settlement', sort = 6, group_name = '结算', path = '/settlement?tab=settlements'
 WHERE menu_no LIKE 'M_tmp154_%' AND path IN ('/finance?tab=settlements', '/settlement?tab=settlements');
UPDATE iam_menu SET menu_no = 'M_settlement__7', parent_no = 'M_settlement', sort = 7, group_name = '付款', path = '/settlement?tab=withdrawals'
 WHERE menu_no LIKE 'M_tmp154_%' AND path IN ('/finance?tab=withdrawals', '/settlement?tab=withdrawals');
UPDATE iam_menu SET menu_no = 'M_settlement__8', parent_no = 'M_settlement', sort = 8, group_name = '付款', path = '/settlement?tab=payout-accounts'
 WHERE menu_no LIKE 'M_tmp154_%' AND path IN ('/finance?tab=payout-accounts', '/settlement?tab=payout-accounts');
UPDATE iam_menu SET menu_no = 'M_finance__1', parent_no = 'M_finance', sort = 1
 WHERE menu_no LIKE 'M_tmp154_%' AND path = '/finance?tab=ledger';
UPDATE iam_menu SET menu_no = 'M_finance__2', parent_no = 'M_finance', sort = 2
 WHERE menu_no LIKE 'M_tmp154_%' AND path = '/finance?tab=reconcile';
UPDATE iam_menu SET menu_no = 'M_finance__3', parent_no = 'M_finance', sort = 3
 WHERE menu_no LIKE 'M_tmp154_%' AND path = '/finance?tab=invoices';

-- 结算插在财务管理之前，其后 L1 排序后移一位
UPDATE iam_menu SET sort = 11 WHERE menu_no = 'M_settlement';
UPDATE iam_menu SET sort = 12 WHERE menu_no = 'M_finance';
UPDATE iam_menu SET sort = 13 WHERE menu_no = 'M_user';
UPDATE iam_menu SET sort = 14 WHERE menu_no = 'M_report';
UPDATE iam_menu SET sort = 15 WHERE menu_no = 'M_org';
UPDATE iam_menu SET sort = 16 WHERE menu_no = 'M_system';
UPDATE iam_menu SET sort = 17 WHERE menu_no = 'M_flow';
