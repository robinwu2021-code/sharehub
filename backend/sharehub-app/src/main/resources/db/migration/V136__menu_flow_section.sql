-- ============================================================
-- ShareHub · 菜单加一个 L1：流程与工单（7 个叶子）
--
-- 【是什么】OpsKit 的运营界面（ops-web /flow/*，前端方案 v4/16/22）：
-- 待办中心 · 审批列表 · 审核队列 · 工单工作台 · 流程配置 · 运营配置 · 接入管理。
-- 权限码强制方是 OpsKit（功能权限清单 §16；码目录见 V135）。
--
-- 【为什么只插不改】与 V132 同口径：整表重灌会冲掉「菜单管理」里的线上改动。
-- 所以这个 L1 在 nav.ts 里**放在最末**（sort = 19，系统设置是 18）：
-- L1 的 sort 就是 nav.ts 里的数组下标，插在中间会让后面每个 L1 的 sort 都要挪。
--
-- 【上线后看得到但点不开 —— 这是有意的】
-- 这些页面的取数目前是浏览器内 mock，不连任何后端。真实后端模式的产物里，
-- 前端按构建期常量（backend-ready.ts FLOW_BACKEND_READY）把 7 个叶子全部灰显，
-- 深链进来也只显示「尚未接入」。库里没有 soon 这一列 —— 那是构建的属性，不是菜单的属性。
--
-- **永远新开一个迁移，不要改已跑过的** —— 别人的库可能已经跑过它，改了 Flyway 会因校验和不符拒绝启动。
-- ============================================================
SET NAMES utf8mb4;

-- 幂等：menu_no 是业务键，已存在就不再插
INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT * FROM (
  SELECT 'M_flow' AS menu_no, NULL AS parent_no, '流程与工单' AS name, 'Workflow & Tickets' AS name_en, 'سير العمل والتذاكر' AS name_ar,
         'MENU' AS type, '/flow/todo' AS path, 'Workflow' AS icon, NULL AS group_name, 19 AS sort, NULL AS perm,
         1 AS phase, 0 AS ready, 'flow' AS module, NULL AS modules, '["/flow"]' AS match_paths, 0 AS pin_bottom, NULL AS portal_for
  UNION ALL SELECT 'M_flow__1', 'M_flow', '待办中心', 'My Tasks', 'مهامي', 'ITEM', '/flow/todo', NULL, '我的待办', 1, 'flow:todo:read', 1, 0, NULL, NULL, NULL, 0, NULL
  UNION ALL SELECT 'M_flow__2', 'M_flow', '审批列表', 'Approvals', 'الموافقات', 'ITEM', '/flow/approvals', NULL, '审批', 2, 'flow:approval:read', 1, 0, NULL, NULL, NULL, 0, NULL
  UNION ALL SELECT 'M_flow__3', 'M_flow', '审核队列', 'Review Queue', 'قائمة المراجعة', 'ITEM', '/flow/queue', NULL, '审批', 3, 'flow:queue:claim', 1, 0, NULL, NULL, NULL, 0, NULL
  UNION ALL SELECT 'M_flow__4', 'M_flow', '工单工作台', 'Ticket Desk', 'مكتب التذاكر', 'ITEM', '/flow/tickets', NULL, '工单', 4, 'flow:ticket:read', 1, 0, NULL, NULL, NULL, 0, NULL
  UNION ALL SELECT 'M_flow__5', 'M_flow', '流程配置', 'Approval Templates', 'قوالب الموافقة', 'ITEM', '/flow/processes', NULL, '配置', 5, 'flow:process:manage', 1, 0, NULL, NULL, NULL, 0, NULL
  UNION ALL SELECT 'M_flow__6', 'M_flow', '运营配置', 'SLA & Queues', 'اتفاقيات الخدمة والقوائم', 'ITEM', '/flow/settings', NULL, '配置', 6, 'flow:config:manage', 1, 0, NULL, NULL, NULL, 0, NULL
  UNION ALL SELECT 'M_flow__7', 'M_flow', '接入管理', 'Integrations', 'التكاملات', 'ITEM', '/flow/access', NULL, '配置', 7, 'flow:access:manage', 1, 0, NULL, NULL, NULL, 0, NULL
) AS v
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = v.menu_no);
