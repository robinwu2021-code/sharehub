-- 权限码目录：flow:approval:submit（运营台「发起审批」，按审批模板的发起表单；强制方 OpsKit approval:submit，功能权限清单 §16 / §16.1）
-- 进目录的理由同 V135：自定义角色要在勾选树里选得到。内置角色由 RolePerms 给（OPS / CS / FINANCE / BD；ADMIN 通配）。
SET NAMES utf8mb4;
INSERT INTO iam_permission (code, name, module) VALUES
  ('flow:approval:submit', '流程与工单 · 发起审批', 'flow')
ON DUPLICATE KEY UPDATE name = VALUES(name), module = VALUES(module);
