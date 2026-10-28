-- ============================================================
-- V168 商家一期 B2：登录标识邮箱优先、手机号可空、未验证的标识不占唯一
-- （需求方案 §四 R1–R8，技术方案 §2.1，差距 G3 / G4）
--
-- 【为什么唯一键挂在「已验证才非空」的生成列上】
-- 运营代建时录入的邮箱与手机号都只是「据说是他的」。录错一位，若直接占唯一键：
--   · 真正的号主来开户时撞键，被一个陌生人的错字挡在门外；
--   · 更糟的是，录错的邮箱收到邀请，陌生人用它登录进别人的商家。
-- 所以只有**本人用验证码证明过**的标识才占唯一（R3 / R4 / R6）。
--
-- ⚠️ 这里是**有意**利用「唯一键不约束 NULL」。V48 注释警告过「可空列进唯一键等于没进」，
-- 那是在需要约束时踩的坑；这里要的恰好是「未验证的不约束」，同一机制反过来用。
-- 别照着 V48 的注释把它「修」成非空 —— 那会让录错的号码重新挡住号主。
--
-- 存量：一期之前代理只能用手机号 + 验证码登录，所以有手机号的自然人视为手机已验证，
-- 原唯一约束的效果对存量不变；邮箱一律记未验证，等首登时验证（R4）。
-- 本文件只用 MySQL 与 MariaDB 都认的语法（不写 IF NOT EXISTS）。
-- ============================================================

ALTER TABLE agt_principal
  MODIFY COLUMN phone_hash VARCHAR(64) NULL COMMENT 'HMAC-SHA256(规范化手机号, pepper)；查找键。可空：手机号选填（R2）',
  MODIFY COLUMN phone_mask VARCHAR(32) NULL COMMENT '仅显示，不得进唯一键或等值条件',
  ADD COLUMN phone_verified TINYINT(1) NOT NULL DEFAULT 0 COMMENT '手机号已由本人验证 0/1；验证后才可用手机号登录（R5）' AFTER phone_mask,
  ADD COLUMN email_verified TINYINT(1) NOT NULL DEFAULT 0 COMMENT '邮箱已由本人验证 0/1；邮箱验证码首登成功即置 1（R4）' AFTER email_mask;

UPDATE agt_principal SET phone_verified = 1 WHERE phone_hash IS NOT NULL;

ALTER TABLE agt_principal
  ADD COLUMN phone_uk VARCHAR(64) AS (CASE WHEN phone_verified = 1 THEN phone_hash END) STORED
    COMMENT '已验证才取 phone_hash，否则 NULL —— 唯一键挂这里，未验证的不占唯一（R6）',
  ADD COLUMN email_uk VARCHAR(64) AS (CASE WHEN email_verified = 1 THEN email_hash END) STORED
    COMMENT '已验证才取 email_hash，否则 NULL —— 唯一键挂这里，未验证的不占唯一（R6）',
  DROP INDEX uk_agt_principal_phone,
  DROP INDEX uk_agt_principal_email,
  ADD UNIQUE KEY uk_agt_principal_phone_verified (phone_uk),
  ADD UNIQUE KEY uk_agt_principal_email_verified (email_uk),
  ADD KEY idx_agt_principal_phone (phone_hash),
  ADD KEY idx_agt_principal_email (email_hash);

-- 申请单：手机号选填；在途唯一从按手机号改为按邮箱（邮箱是必填的主标识，R1）
ALTER TABLE agt_apply
  MODIFY COLUMN phone_hash VARCHAR(64) NULL COMMENT 'HMAC(规范化手机号)；选填（R2）',
  MODIFY COLUMN phone_mask VARCHAR(32) NULL COMMENT '仅显示';

ALTER TABLE agt_apply
  DROP INDEX uk_agt_apply_active,
  DROP COLUMN active_key;

ALTER TABLE agt_apply
  ADD COLUMN active_key VARCHAR(64) AS (CASE WHEN status IN ('DRAFT','SUBMITTED','REVIEWING')
                                             THEN email_hash ELSE apply_no END) STORED
    COMMENT '在途取 email_hash、终态取 apply_no；全程非空，约束真实生效（V168 起按邮箱）',
  ADD UNIQUE KEY uk_agt_apply_active (active_key);
