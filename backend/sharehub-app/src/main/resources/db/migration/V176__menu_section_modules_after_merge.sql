-- 合并 L1 之后补回「按模块可达」（MenuPermissionContractTest.everyBackendResourceIsReachable）。
--
-- OM04 / OM05 / OM11（V15x ~ V172）撤掉了「营销管理」（module marketing）、「员工与权限」（module org）、
-- 「运营管理」（modules 含 pricing），页面并入销售管理、用户管理、系统管理，但这三个模块前缀没跟过去：
-- marketing:recharge · marketing:referral · org:dept · org:performance · pricing:rule 五个资源后端有端点、菜单无入口。
--
-- 只补 modules，不改任何叶子的 perm：有叶子的 L1 可见性 = 有没有可见叶子，modules 不改变谁看得到什么
-- （nav-visibility 快照不变）。
SET NAMES utf8mb4;

UPDATE iam_menu SET modules = '["order","marketing","pricing"]' WHERE menu_no = 'M_order';
UPDATE iam_menu SET modules = '["user","marketing"]'            WHERE menu_no = 'M_user';
UPDATE iam_menu SET modules = '["system","org"]'                WHERE menu_no = 'M_system';
