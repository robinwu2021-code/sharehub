-- ============================================================
-- ShareHub · 权限码目录：流程与工单 flow（10 条）
--
-- 【为什么要进目录】
-- 这 10 个码**不由 ShareHub 端点强制**，由 OpsKit 强制（功能权限清单 §16）。
-- 但「谁持有」在 ShareHub：内置角色由 RolePerms 给（IamSeeder 每次启动增量补进 iam_role_perm），
-- 自定义角色则要管理员在角色勾选树里选 —— 勾选树从 iam_permission 渲染，
-- 不进目录，管理员就**选不到**，自定义角色永远用不了流程与工单。
--
-- 【为什么不让 gen-perm-catalog.py 生成】
-- 生成器扫的是 @perm.can（ShareHub 端点强制的码），扫不到外部强制模块。
-- 中文名同时登记进 backend/scripts/perm-catalog-names.tsv，日后重新生成时名字不丢。
--
-- ON DUPLICATE KEY UPDATE：与 V72 同口径，重跑只更新名字，不删不重建。
-- ============================================================
SET NAMES utf8mb4;

INSERT INTO iam_permission (code, name, module) VALUES
  ('flow:todo:read', '流程与工单 · 待办中心', 'flow'),
  ('flow:approval:read', '流程与工单 · 审批 查看', 'flow'),
  ('flow:approval:decide', '流程与工单 · 审批 通过/驳回', 'flow'),
  ('flow:queue:claim', '流程与工单 · 审核队列 领取/决定', 'flow'),
  ('flow:ticket:read', '流程与工单 · 工单 查看', 'flow'),
  ('flow:ticket:handle', '流程与工单 · 工单 办理/抢单', 'flow'),
  ('flow:ticket:dispatch', '流程与工单 · 工单 派单/改派/升级', 'flow'),
  ('flow:process:manage', '流程与工单 · 流程配置', 'flow'),
  ('flow:config:manage', '流程与工单 · 运营配置（SLA/日历/队列）', 'flow'),
  ('flow:access:manage', '流程与工单 · 接入管理（应用/凭证/投递）', 'flow')
ON DUPLICATE KEY UPDATE name = VALUES(name), module = VALUES(module);
