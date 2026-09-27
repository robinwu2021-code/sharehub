-- ============================================================
-- ShareHub · 菜单加一条叶子：用户管理 › 会员套餐
--
-- 【为什么加一条菜单要写迁移】
-- 菜单真源 V67 起在库里，所以菜单的维护入口本身也是一条菜单。
-- 这是「菜单真源迁进库」的代价，V81 / V89 的注释都记过。
--
-- 【这条是干什么的】
-- mbr_plan 此前**没有任何写入方**：有表、有实体、有 mapper，C 端有会员方案页读它，
-- 出参 DTO 还注明「运营端方案维护」—— 但全仓没有一个端点能建或改它，表里 0 行。
-- 于是 C 端会员页永远是空列表，看起来像「本平台暂无会员方案」。
-- 后端端点与页面在同一个提交里落好（user:member:read / :update，权限码已存在）。
--
-- 【为什么是 INSERT 一行而不是整表重灌】
-- V89 是整表重灌的，但那之后口径改了（见 ops-web/lib/nav-seed.test.ts 的注释）：
-- **整表重灌会冲掉「菜单管理」里的线上改动**。
-- 所以这条叶子在 nav.ts 里刻意放在本菜单**末尾** —— 往「用户资产」组中间插的话，
-- 同组后面所有叶子的 sort 都要挪，而那就只能整表重灌。
-- 放末尾则 sort 是个新号（10），插一行即可，不动任何既有行。
--
-- **永远新开一个迁移，不要改 V89** —— 别人的库可能已经跑过它，
-- 改了 Flyway 会因校验和不符拒绝启动。
-- ============================================================
SET NAMES utf8mb4;

-- 幂等：重复执行不再插一行（menu_no 是业务键）
INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT 'M_user__10', 'M_user', '会员套餐', 'Membership Plans', 'خطط العضوية', 'ITEM', '/users?tab=plans', NULL, '用户资产', 10, 'user:member:read', 2, 0, NULL, NULL, NULL, 0, NULL
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = 'M_user__10');
