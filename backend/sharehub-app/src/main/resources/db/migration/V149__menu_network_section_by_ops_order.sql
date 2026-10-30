-- ============================================================
-- ShareHub · 菜单：「场地方与拓展」+ 运营管理的站点四项 →「网点管理」（OM02）
--
-- 【为什么】
-- 2026-10-28 用户确认运营端菜单按运营顺序排列
-- （docs/requirements/运营端菜单清单-按运营顺序.md）。网点管理承接 P1 拓场 → P2 签约 → P3 建站：
--   拓展：BD 拓展 CRM · 门店 Onboarding
--   场地方与合同：场地方 · 进场合同
--   站点与点位：站点管理 · 点位管理 · 站点概览 · 站点坪效 · 门店生命周期
-- 站点四项此前挂在「运营管理」下；运营管理暂留计费、基础、分成三组，待对应模块调整时迁走。
--
-- 【改了什么】
--   · M_venue 更名「网点管理」，首页改为站点管理（落地页由 section.href 决定），
--     路径归属加上 /locations、/operation/sites、/operation/overview（最长前缀胜过运营管理的 /operation）。
--   · M_operation 首页改为收费方案，路径归属只留 /operation。
--   · 两段的叶子重编号：编号是数组下标（nav-seed.test.ts），四项跨段移动后两段都要重排。
--   · M_agent 首页改为入驻审核：落地页改为先认 section.href 后，原首页 /agents 会把落地变成商家档案。
--
-- 【做法】先把两段叶子全部改成按 id 生成的临时编号，再按 path 赋最终编号与分组。
-- path 不能在「菜单管理」里在线改，所以 path → 页面是确定的；按行更新保留 id、created_at
-- 与在线设置过的 visible。重复执行结果相同（第一步总会把编号重新打散，第二步按 path 赋回）。
-- **永远新开一个迁移，不要改本文件。**
-- ============================================================
SET NAMES utf8mb4;

UPDATE iam_menu SET name = '网点管理', name_en = 'Network', name_ar = 'إدارة الشبكة',
       path = '/operation/sites',
       match_paths = '["/venues","/locations","/operation/sites","/operation/overview"]'
 WHERE menu_no = 'M_venue';

UPDATE iam_menu SET path = '/operation/fee-plans', match_paths = '["/operation"]'
 WHERE menu_no = 'M_operation';

UPDATE iam_menu SET path = '/agents?tab=applies' WHERE menu_no = 'M_agent';

-- 第一步：两段叶子打散成临时编号（menu_no 唯一，直接互换会撞键）
UPDATE iam_menu SET menu_no = CONCAT('M_tmp149_', id)
 WHERE parent_no IN ('M_venue', 'M_operation');

-- 第二步：网点管理
UPDATE iam_menu SET menu_no = 'M_venue__1', parent_no = 'M_venue', group_name = '拓展', sort = 1
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/venues?tab=crm';
UPDATE iam_menu SET menu_no = 'M_venue__2', parent_no = 'M_venue', group_name = '拓展', sort = 2
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/venues?tab=onboarding';
UPDATE iam_menu SET menu_no = 'M_venue__3', parent_no = 'M_venue', group_name = '场地方与合同', sort = 3
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/venues?tab=venues';
UPDATE iam_menu SET menu_no = 'M_venue__4', parent_no = 'M_venue', group_name = '场地方与合同', sort = 4
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/venues?tab=contracts';
UPDATE iam_menu SET menu_no = 'M_venue__5', parent_no = 'M_venue', group_name = '站点与点位', sort = 5
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/operation/sites';
UPDATE iam_menu SET menu_no = 'M_venue__6', parent_no = 'M_venue', group_name = '站点与点位', sort = 6
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/locations?tab=points';
UPDATE iam_menu SET menu_no = 'M_venue__7', parent_no = 'M_venue', group_name = '站点与点位', sort = 7
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/operation/overview';
UPDATE iam_menu SET menu_no = 'M_venue__8', parent_no = 'M_venue', group_name = '站点与点位', sort = 8
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/locations?tab=analysis';
UPDATE iam_menu SET menu_no = 'M_venue__9', parent_no = 'M_venue', group_name = '站点与点位', sort = 9
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/venues?tab=lifecycle';

-- 第二步：运营管理（剩三组，顺序不变，编号前移 4 位）
UPDATE iam_menu SET menu_no = 'M_operation__1', parent_no = 'M_operation', sort = 1
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/operation/fee-plans';
UPDATE iam_menu SET menu_no = 'M_operation__2', parent_no = 'M_operation', sort = 2
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/operation/fee-adjustments';
UPDATE iam_menu SET menu_no = 'M_operation__3', parent_no = 'M_operation', sort = 3
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/operation/app-versions';
UPDATE iam_menu SET menu_no = 'M_operation__4', parent_no = 'M_operation', sort = 4
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/operation/banks';
UPDATE iam_menu SET menu_no = 'M_operation__5', parent_no = 'M_operation', sort = 5
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/operation/brands';
UPDATE iam_menu SET menu_no = 'M_operation__6', parent_no = 'M_operation', sort = 6
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/operation/problems';
UPDATE iam_menu SET menu_no = 'M_operation__7', parent_no = 'M_operation', sort = 7
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/operation/notices';
UPDATE iam_menu SET menu_no = 'M_operation__8', parent_no = 'M_operation', sort = 8
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/operation/site-sharing';
UPDATE iam_menu SET menu_no = 'M_operation__9', parent_no = 'M_operation', sort = 9
 WHERE menu_no LIKE 'M_tmp149_%' AND path = '/operation/payee-sharing';
