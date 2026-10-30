-- ============================================================
-- V185 第 6 批（运营方原始需求 §3.2 P1，2026-10-29）
--   · MER-01：商家档案补营业执照号（TRN 已在 V183 补）
--   · MER-08：运维（OPS）补合同只读 —— 上门要知道站点合同还有多久、谁签的
-- 本文件只用 MySQL 与 MariaDB 都认的语法。
-- ============================================================
ALTER TABLE agt_agent
  ADD COLUMN license_no VARCHAR(64) NULL COMMENT '营业执照号（MER-01）';

INSERT IGNORE INTO iam_role_perm (role_no, perm_code) VALUES
  ('OPS', 'location:contract:read');
