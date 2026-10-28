-- ============================================================
-- ShareHub · 菜单：「销售管理」（OM04）—— 订单管理 + 营销管理 + 计费 + 会员 + 支付渠道
--
-- 【为什么】
-- 2026-10-28 用户确认运营端菜单按运营顺序排列
-- （docs/requirements/运营端菜单清单-按运营顺序.md）。销售管理承接收入线：
--   计费 → 订单 → 支付 → 售后 → 营销 → 会员 → 广告
-- 来源：订单管理（M_order，更名）· 营销管理（M_marketing，整段并入后删除该 L1）·
--       运营管理的收费方案 / 预约调价 · 用户管理的会员套餐 / 会员次卡 · 系统设置的支付渠道。
-- 后三者页面搬到 /orders 的 tab（payment-channels / plans / members），故这三行 path 一并改。
--
-- 【做法】五段叶子先按 id 打散成临时编号，再按 path 赋最终编号、所属段与分组（新旧 path 都认，
-- 重复执行结果相同）。按行更新保留 id、created_at 与在线设置过的 visible。
-- 删除 M_marketing 后其后的 L1 排序前移一位（L1 的 sort 是 nav.ts 数组下标）。
-- **永远新开一个迁移，不要改本文件。**
-- ============================================================
SET NAMES utf8mb4;

UPDATE iam_menu SET name = '销售管理', name_en = 'Sales', name_ar = 'المبيعات',
       match_paths = '["/orders","/marketing","/operation/fee-plans","/operation/fee-adjustments"]'
 WHERE menu_no = 'M_order';
UPDATE iam_menu SET path = '/operation/app-versions' WHERE menu_no = 'M_operation';
UPDATE iam_menu SET path = '/system?tab=notify' WHERE menu_no = 'M_system';

-- 第一步：五段叶子打散
UPDATE iam_menu SET menu_no = CONCAT('M_tmp151_', id)
 WHERE parent_no IN ('M_order', 'M_marketing', 'M_operation', 'M_user', 'M_system');

-- 第二步：按 path 赋最终编号
UPDATE iam_menu SET menu_no = 'M_order__1', parent_no = 'M_order', sort = 1, group_name = '计费'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/operation/fee-plans';
UPDATE iam_menu SET menu_no = 'M_order__2', parent_no = 'M_order', sort = 2, group_name = '计费'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/operation/fee-adjustments';
UPDATE iam_menu SET menu_no = 'M_order__3', parent_no = 'M_order', sort = 3, group_name = '订单'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/orders';
UPDATE iam_menu SET menu_no = 'M_order__4', parent_no = 'M_order', sort = 4, group_name = '订单'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/orders?tab=reservations';
UPDATE iam_menu SET menu_no = 'M_order__5', parent_no = 'M_order', sort = 5, group_name = '订单'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/orders?tab=deposit';
UPDATE iam_menu SET menu_no = 'M_order__6', parent_no = 'M_order', sort = 6, group_name = '订单'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/orders?tab=free';
UPDATE iam_menu SET menu_no = 'M_order__7', parent_no = 'M_order', sort = 7, group_name = '支付', path = '/orders?tab=payment-channels'
 WHERE menu_no LIKE 'M_tmp151_%' AND path IN ('/system?tab=payment', '/orders?tab=payment-channels');
UPDATE iam_menu SET menu_no = 'M_order__8', parent_no = 'M_order', sort = 8, group_name = '售后'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/orders?tab=exceptions';
UPDATE iam_menu SET menu_no = 'M_order__9', parent_no = 'M_order', sort = 9, group_name = '售后'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/orders?tab=complaints';
UPDATE iam_menu SET menu_no = 'M_order__10', parent_no = 'M_order', sort = 10, group_name = '售后'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/orders?tab=refunds';
UPDATE iam_menu SET menu_no = 'M_order__11', parent_no = 'M_order', sort = 11, group_name = '营销'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/marketing';
UPDATE iam_menu SET menu_no = 'M_order__12', parent_no = 'M_order', sort = 12, group_name = '营销'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/marketing?tab=campaigns';
UPDATE iam_menu SET menu_no = 'M_order__13', parent_no = 'M_order', sort = 13, group_name = '营销'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/marketing?tab=push';
UPDATE iam_menu SET menu_no = 'M_order__14', parent_no = 'M_order', sort = 14, group_name = '营销'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/marketing?tab=referral';
UPDATE iam_menu SET menu_no = 'M_order__15', parent_no = 'M_order', sort = 15, group_name = '会员', path = '/orders?tab=plans'
 WHERE menu_no LIKE 'M_tmp151_%' AND path IN ('/users?tab=plans', '/orders?tab=plans');
UPDATE iam_menu SET menu_no = 'M_order__16', parent_no = 'M_order', sort = 16, group_name = '会员', path = '/orders?tab=members'
 WHERE menu_no LIKE 'M_tmp151_%' AND path IN ('/users?tab=members', '/orders?tab=members');
UPDATE iam_menu SET menu_no = 'M_order__17', parent_no = 'M_order', sort = 17, group_name = '广告'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/marketing?tab=ad-slots';
UPDATE iam_menu SET menu_no = 'M_order__18', parent_no = 'M_order', sort = 18, group_name = '广告'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/marketing?tab=ad-campaigns';
UPDATE iam_menu SET menu_no = 'M_order__19', parent_no = 'M_order', sort = 19, group_name = '广告'
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/marketing?tab=ad-delivery';
UPDATE iam_menu SET menu_no = 'M_operation__1', parent_no = 'M_operation', sort = 1
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/operation/app-versions';
UPDATE iam_menu SET menu_no = 'M_operation__2', parent_no = 'M_operation', sort = 2
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/operation/banks';
UPDATE iam_menu SET menu_no = 'M_operation__3', parent_no = 'M_operation', sort = 3
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/operation/brands';
UPDATE iam_menu SET menu_no = 'M_operation__4', parent_no = 'M_operation', sort = 4
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/operation/problems';
UPDATE iam_menu SET menu_no = 'M_operation__5', parent_no = 'M_operation', sort = 5
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/operation/notices';
UPDATE iam_menu SET menu_no = 'M_operation__6', parent_no = 'M_operation', sort = 6
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/operation/site-sharing';
UPDATE iam_menu SET menu_no = 'M_operation__7', parent_no = 'M_operation', sort = 7
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/operation/payee-sharing';
UPDATE iam_menu SET menu_no = 'M_user__1', parent_no = 'M_user', sort = 1
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/users';
UPDATE iam_menu SET menu_no = 'M_user__2', parent_no = 'M_user', sort = 2
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/users?tab=logoffs';
UPDATE iam_menu SET menu_no = 'M_user__3', parent_no = 'M_user', sort = 3
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/users?tab=risk';
UPDATE iam_menu SET menu_no = 'M_user__4', parent_no = 'M_user', sort = 4
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/users?tab=blacklist';
UPDATE iam_menu SET menu_no = 'M_user__5', parent_no = 'M_user', sort = 5
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/users?tab=whitelist';
UPDATE iam_menu SET menu_no = 'M_user__6', parent_no = 'M_user', sort = 6
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/users?tab=wallets';
UPDATE iam_menu SET menu_no = 'M_user__7', parent_no = 'M_user', sort = 7
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/users?tab=recharge';
UPDATE iam_menu SET menu_no = 'M_user__8', parent_no = 'M_user', sort = 8
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/users?tab=invoices';
UPDATE iam_menu SET menu_no = 'M_system__1', parent_no = 'M_system', sort = 1
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/system?tab=notify';
UPDATE iam_menu SET menu_no = 'M_system__2', parent_no = 'M_system', sort = 2
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/system?tab=notify-log';
UPDATE iam_menu SET menu_no = 'M_system__3', parent_no = 'M_system', sort = 3
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/system?tab=notify-blacklist';
UPDATE iam_menu SET menu_no = 'M_system__4', parent_no = 'M_system', sort = 4
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/system?tab=rules';
UPDATE iam_menu SET menu_no = 'M_system__5', parent_no = 'M_system', sort = 5
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/system?tab=login';
UPDATE iam_menu SET menu_no = 'M_system__6', parent_no = 'M_system', sort = 6
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/system?tab=dict';
UPDATE iam_menu SET menu_no = 'M_system__7', parent_no = 'M_system', sort = 7
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/system?tab=region';
UPDATE iam_menu SET menu_no = 'M_system__8', parent_no = 'M_system', sort = 8
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/system?tab=params';
UPDATE iam_menu SET menu_no = 'M_system__9', parent_no = 'M_system', sort = 9
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/system?tab=tax';
UPDATE iam_menu SET menu_no = 'M_system__10', parent_no = 'M_system', sort = 10
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/system?tab=markets';
UPDATE iam_menu SET menu_no = 'M_system__11', parent_no = 'M_system', sort = 11
 WHERE menu_no LIKE 'M_tmp151_%' AND path = '/system?tab=openapi';

-- 营销管理 L1 叶子已全部迁走，删除该 L1 行；其后 L1 排序前移一位
DELETE FROM iam_menu WHERE menu_no = 'M_marketing' AND parent_no IS NULL;
UPDATE iam_menu SET sort = 14 WHERE menu_no = 'M_cs';
UPDATE iam_menu SET sort = 15 WHERE menu_no = 'M_report';
UPDATE iam_menu SET sort = 16 WHERE menu_no = 'M_org';
UPDATE iam_menu SET sort = 17 WHERE menu_no = 'M_system';
UPDATE iam_menu SET sort = 18 WHERE menu_no = 'M_flow';
