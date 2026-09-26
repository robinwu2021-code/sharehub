SET NAMES utf8mb4;
-- ============================================================
-- 权限码目录删掉三个废弃的工单码
--
-- 【它们是什么】`workorder:wo:audit` / `:process` 是 2026-07-29 就统一为
--   `:close` / `:handle` 的旧名；`workorder:wo:update` 是从未进过 [功能权限清单] 的表外码
--   （巡检计划配置此前借用它，2026-09-26 已改判 `workorder:inspection:update`）。
--   端点侧的改判同一批完成，所以现在**没有任何端点判这三个码**。
--
-- 【为什么必须从目录里删】`iam_permission` 是「配角色时能勾什么」的来源。
--   留着的后果不是报错，是**管理员勾了它却什么也没发生** ——
--   他以为给某个角色开了「工单审核」，而没有任何端点认这个码。
--   这类「配了等于没配」比缺功能更难查：界面上明明有这一项，勾选也保存成功了。
--
-- 【安全性】本机与生产实测 `iam_role_perm` 里这三个码的绑定数都是 0，删除不影响任何已配角色。
--   若将来某个库里有绑定，下面的 DELETE 会一并清掉那条无效绑定（它本来也不生效）。
--
-- 【为什么不改 V100】那是已发布的迁移，改了 Flyway 会因校验和不符拒绝启动。
-- ============================================================

DELETE FROM iam_role_perm WHERE perm_code IN ('workorder:wo:audit', 'workorder:wo:process', 'workorder:wo:update');
DELETE FROM iam_permission WHERE code IN ('workorder:wo:audit', 'workorder:wo:process', 'workorder:wo:update');
