-- ============================================================
-- V173 收款账号明文存储（用户 2026-10-28：收款账号不属于敏感信息，不必依赖 pii-key）
--
-- V158 把完整 IBAN 只存成 FieldCipher 密文，而 sharehub.security.pii-key 没配时 FieldCipher 不加密、
-- 什么都不存 —— 结果「录了收款账户，付款任务仍报缺完整账号」。按用户裁决改为明文列：
-- 录入时写 account_full，导出转账文件直接读它；account_enc 保留只作老数据回落，不再写。
-- 本文件只用 MySQL 与 MariaDB 都认的语法。
-- ============================================================
ALTER TABLE stl_payout_account
  ADD COLUMN account_full VARCHAR(64) NULL COMMENT '完整收款账号（IBAN，去空格大写）；导出转账文件用。空 = 需补录' AFTER account_masked;
