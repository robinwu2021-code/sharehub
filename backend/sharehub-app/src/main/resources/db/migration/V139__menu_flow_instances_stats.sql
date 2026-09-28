-- ============================================================
-- ShareHub · 「流程与工单」下加两个叶子：流程实例 · 流程统计（新分组「流转与统计」）
--
-- 【是什么】OpsKit 流程层 O2-10 的界面（ops-web /flow/instances、/flow/stats）：
-- 流程实例 = 实例列表 + 单个实例的完整流转历史 + 按业务对象对账；流程统计 = 各流程的量、未结、用时、审批结果。
-- 权限码复用既有码（功能权限清单 §16），不新增：实例与对账跟审批同一可见口径 → flow:approval:read；
-- 统计是管理视角 → flow:process:manage。强制方仍是 OpsKit。
--
-- 【为什么只插不改、追加在末尾】与 V132 / V136 同口径：整表重灌会冲掉「菜单管理」里的线上改动；
-- 叶子的 sort 接着 V136 的 7 往后排（8、9），不往既有分组中间插 —— 插在中间就得挪后面每个叶子的 sort。
--
-- 【上线后同样灰显】取数在真实后端模式下仍受 backend-ready.ts FLOW_BACKEND_READY 控制，与 V136 的 7 个叶子一致。
-- 迁移号：V138 已被另一会话的在途文件（withdrawal_same_operator）占用，这里用 V139。
-- **永远新开一个迁移，不要改已跑过的。**
-- ============================================================
SET NAMES utf8mb4;

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
SELECT * FROM (
  SELECT 'M_flow__8' AS menu_no, 'M_flow' AS parent_no, '流程实例' AS name, 'Process Instances' AS name_en, 'مثيلات المسارات' AS name_ar,
         'ITEM' AS type, '/flow/instances' AS path, NULL AS icon, '流转与统计' AS group_name, 8 AS sort, 'flow:approval:read' AS perm,
         1 AS phase, 0 AS ready, NULL AS module, NULL AS modules, NULL AS match_paths, 0 AS pin_bottom, NULL AS portal_for
  UNION ALL SELECT 'M_flow__9', 'M_flow', '流程统计', 'Process Stats', 'إحصاءات المسارات', 'ITEM', '/flow/stats', NULL, '流转与统计', 9, 'flow:process:manage', 1, 0, NULL, NULL, NULL, 0, NULL
) AS v
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_no FROM iam_menu) t WHERE t.menu_no = v.menu_no);
