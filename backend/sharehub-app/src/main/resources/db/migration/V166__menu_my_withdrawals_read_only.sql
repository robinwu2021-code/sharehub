-- ============================================================
-- ShareHub · 菜单：商家门户「我的经营 › 申请提现」改为「提现记录」，改挂 finance:withdrawal:read
--
-- 【为什么】
-- 商家一期 D8：提现在平台运营端进行（付款任务），商家只看提现记录、不申请。
-- 商家角色同步由 finance:withdrawal:apply 改为 finance:withdrawal:read（RolePerms / permissions.ts）。
-- 叶子仍挂 :apply 的话，商家会看不到这条菜单。路径不变，按编号 + path 定点更新，重复执行结果相同。
-- **永远新开一个迁移，不要改本文件。**
-- ============================================================
SET NAMES utf8mb4;

UPDATE iam_menu SET name = '提现记录', name_en = 'Withdrawal Records', name_ar = 'سجل السحوبات',
                    perm = 'finance:withdrawal:read'
 WHERE menu_no = 'M_my-biz__4' AND path = '/settlement?tab=withdrawals';
