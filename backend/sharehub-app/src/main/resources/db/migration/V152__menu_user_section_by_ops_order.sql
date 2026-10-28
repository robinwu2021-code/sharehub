-- ============================================================
-- ShareHub · 菜单：用户管理按运营顺序（OM05）—— 用户 → 风险 → 资产
--
-- 【为什么】
-- 2026-10-28 用户确认运营端菜单按运营顺序排列
-- （docs/requirements/运营端菜单清单-按运营顺序.md）。用户管理管「谁在借、能不能借」：
--   用户：用户列表 · 注销申请
--   风险：风控用户 · 黑名单 · 免押白名单（原「免费用户白名单」）
--   资产：钱包 · 充值订单与套餐（原「充值套餐」，充值订单页签自财务管理搬来合并）· 开票申请（待发票模块迁走）
--
-- 【改了什么】
--   · 用户段八行改名 / 改分组（V151 之后编号已是新顺序，不用重排）。
--   · 财务管理「用户账」组两行删除：用户钱包（指向 /users 的重复入口）、充值订单（页签已搬走）。
--     两行都在财务段末尾，删掉不影响前面的编号。
-- 按编号 + path 定点更新，保留 id、created_at 与在线设置过的 visible；重复执行结果相同。
-- **永远新开一个迁移，不要改本文件。**
-- ============================================================
SET NAMES utf8mb4;

UPDATE iam_menu SET group_name = '用户' WHERE menu_no IN ('M_user__1', 'M_user__2') AND parent_no = 'M_user';
UPDATE iam_menu SET group_name = '风险' WHERE menu_no IN ('M_user__3', 'M_user__4') AND parent_no = 'M_user';
UPDATE iam_menu SET group_name = '风险', name = '免押白名单', name_en = 'Deposit-free Whitelist',
       name_ar = 'القائمة البيضاء المعفاة من التأمين'
 WHERE menu_no = 'M_user__5' AND path = '/users?tab=whitelist';
UPDATE iam_menu SET group_name = '资产' WHERE menu_no IN ('M_user__6', 'M_user__8') AND parent_no = 'M_user';
UPDATE iam_menu SET group_name = '资产', name = '充值订单与套餐', name_en = 'Top-ups & Packages', name_ar = 'الشحن والباقات'
 WHERE menu_no = 'M_user__7' AND path = '/users?tab=recharge';

DELETE FROM iam_menu WHERE menu_no = 'M_finance__11' AND path = '/users?tab=wallets';
DELETE FROM iam_menu WHERE menu_no = 'M_finance__12' AND path = '/finance?tab=recharges';
