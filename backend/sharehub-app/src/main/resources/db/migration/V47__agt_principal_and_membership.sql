-- ============================================================
-- ShareHub · 代理端自然人（登录主体）+ 账号改为「人 × 主体」成员关系
--
-- 依据：ADR-030 §2.2–2.5（一个账号可属多个运营主体）
-- 对应：TDD-运营主体与商户 的「V52」——**实际编号取 V47**，因为 Flyway 未开
--       out-of-order，而 V46 已被另一会话（ADR-028 取价）占用。
--
-- 【为什么不是「给 agt_account 加两列」这么简单】
--  1. 用户 2026-09-23 定「注册信息包含手机号码、邮件」：两个登录标识 + 一套凭据
--     不能在 N 行成员关系上冗余 —— 改邮箱要改 N 行，漏一行就是一个还能登的旧邮箱。
--  2. 现有 agt_account.login_phone 存的是**掩码**（V13 列注释原文「明文脱敏值」）。
--     13800138000 与 13811138000 的掩码都是 138****8000 ——
--     **拿它做唯一键，两个不同的人会被判为重复**；掩码不可逆，也没法按它查找。
--
-- 【三列分工，必须守住】
--   *_hash  登录查找 + 唯一约束   HMAC-SHA256(规范化值, pepper)
--   *_mask  只用于显示           绝不进唯一键，绝不做等值条件
--   *_enc   需要还原时（发短信/邮件）· pepper 轮换时重算 hash 的唯一依据
-- ============================================================

CREATE TABLE IF NOT EXISTS agt_principal (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  principal_no VARCHAR(36)  NOT NULL                COMMENT '业务键 PR*',
  phone_hash   VARCHAR(64)  NOT NULL                COMMENT 'HMAC-SHA256(规范化手机号, pepper)；登录查找键',
  phone_mask   VARCHAR(32)  NOT NULL                COMMENT '138****8000；仅显示，不得进唯一键或等值条件',
  phone_enc    VARBINARY(256)   NULL                COMMENT '可逆加密明文；pb_pii 建成后迁出',
  email_hash   VARCHAR(64)  NOT NULL                COMMENT 'HMAC-SHA256(lower(trim(邮箱)), pepper)',
  email_mask   VARCHAR(64)  NOT NULL                COMMENT 'a***@example.com；仅显示',
  email_enc    VARBINARY(512)   NULL,
  hash_ver     TINYINT      NOT NULL DEFAULT 1      COMMENT 'pepper 版本；轮换靠 *_enc 全表重算后统一切版，唯一键不动',
  cred_ref     VARCHAR(64)      NULL                COMMENT 'sharehub_auth.cred 引用(realm=AGENT)；一个人一套，不按主体分',
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN' COMMENT '历史遗留常量，恒 MAIN（ADR-026），新代码不得据此分支',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(UTC)',
  created_by   VARCHAR(36)      NULL                COMMENT '创建人业务键；无登录态写 SYSTEM',
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(UTC)，DB 自动维护',
  updated_by   VARCHAR(36)      NULL,
  version      BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁',
  deleted      TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除 0/1',
  PRIMARY KEY (id),
  UNIQUE KEY uk_agt_principal_no    (principal_no),
  UNIQUE KEY uk_agt_principal_phone (phone_hash),
  UNIQUE KEY uk_agt_principal_email (email_hash),
  KEY        idx_agt_principal_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理端自然人（登录主体）';

-- ---------- agt_account：成员关系 ----------
ALTER TABLE agt_account
  ADD COLUMN IF NOT EXISTS principal_no VARCHAR(36)  NULL COMMENT '→ agt_principal.principal_no；取代 username/login_phone 做键',
  ADD COLUMN IF NOT EXISTS is_owner     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '主体属主：全站点全权限、不进授权表（ADR-030 §5.1）',
  ADD COLUMN IF NOT EXISTS is_primary   TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '该人的默认主体；同一人至多一个',
  ADD COLUMN IF NOT EXISTS display_name VARCHAR(64)  NULL COMMENT '在该主体下的显示名';

-- ---------- 存量：安全降级，不猜身份 ----------
-- 掩码不可逆，从 138****8000 推不回明文，**任何自动回填都只能填错**。
-- 所以给每一条存量账号派生一个占位自然人，并置 DISABLED：
--   · 占位 hash 由 account_no 派生，**永远不会被真实登录命中**
--     （真实登录算的是 HMAC(手机号)，与这里的 SHA2('LEGACY-…') 不在一个值域）
--   · 置 DISABLED 而不是留空：宁可让人工补资料后再启用，
--     也不要产生一行「能登录但身份不明」的账号
-- 运营补齐真实手机号与邮箱后，把 status 改回 ACTIVE 即可。
INSERT INTO agt_principal
  (principal_no, phone_hash, phone_mask, email_hash, email_mask,
   status, tenant_id, created_at, created_by, updated_at, updated_by)
SELECT CONCAT('PR-LEGACY-', a.account_no),
       SHA2(CONCAT('LEGACY-PHONE-', a.account_no), 256), '（待补）',
       SHA2(CONCAT('LEGACY-EMAIL-', a.account_no), 256), '（待补）',
       'DISABLED', 'MAIN', NOW(3), 'SYSTEM', NOW(3), 'SYSTEM'
  FROM agt_account a
 WHERE a.deleted = 0
   AND a.principal_no IS NULL
   AND NOT EXISTS (SELECT 1 FROM agt_principal p
                    WHERE p.principal_no = CONCAT('PR-LEGACY-', a.account_no));

UPDATE agt_account SET principal_no = CONCAT('PR-LEGACY-', account_no)
 WHERE deleted = 0 AND principal_no IS NULL;

-- ---------- 换键 ----------
-- 先 ADD 新键再 DROP 旧键（同既有约定），缩短唯一性空窗。
-- ⚠️ uk_agt_username (tenant_id, username) 是**单主体限制的来源**，必须删。
ALTER TABLE agt_account ADD UNIQUE KEY IF NOT EXISTS uk_agt_account_member (agent_no, principal_no);
CREATE INDEX IF NOT EXISTS idx_agt_account_pr ON agt_account (principal_no, is_primary);
ALTER TABLE agt_account DROP INDEX IF EXISTS uk_agt_username;

-- username 保留：是否留兼容期未定（ADR-030 §七 3）。
-- 它已不是任何唯一键的一部分，留着不影响多主体。
