-- 权限码目录：付款任务（商家一期 B4，需求方案 §8.7，功能权限清单 §6）。
-- 进目录的理由同 V135：自定义角色要在勾选树里选得到。内置角色由 RolePerms 给（FINANCE 持 finance:*；ADMIN 通配）。
--   finance:withdrawal:create —— 选截止日生成付款任务（发起）
--   finance:withdrawal:export —— 导出含完整 IBAN 的转账文件；单独发码，便于只给少数人
-- 复核沿用 finance:withdrawal:audit，确认到账沿用 finance:withdrawal:pay：发起、复核、确认三步三个码。
SET NAMES utf8mb4;
INSERT INTO iam_permission (code, name, module) VALUES
  ('finance:withdrawal:create', '提现 · 生成付款任务', 'finance'),
  ('finance:withdrawal:export', '提现 · 导出转账文件', 'finance')
ON DUPLICATE KEY UPDATE name = VALUES(name), module = VALUES(module);
