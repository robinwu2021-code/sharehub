-- P3b · B1 凭据底座。**登录一行不改** —— 共享口令闸照常工作，这一步只是把地基铺好。
--
-- 背景（docs/technical/P3b-员工凭据与真实登录-设计.md）：
-- 这个系统至今没有过一套密码凭据。员工端所有人共用 `sharehub.admin.password`，
-- 于是 iam_audit_log 里每一条操作的 actor 都是同一个人 —— **审计等于摆设**，
-- 也无法按人停用、换人要通知所有人改口令。三个后果都不报错。
--
-- ── 一、cred_credential：员工与代理**共用一张** ────────────────────────
-- 不各建一张：两张表意味着两套密码策略、两套锁定逻辑，迟早只改了一边，
-- 而「另一边没改」这件事不会有任何症状——直到某一边被绕过。
-- 用 realm 区分（STAFF / AGENT），与 iam_employee.cred_ref、agt_principal.cred_ref 的
-- 既定引用关系对齐（那两列的注释早就指着这张表，只是表一直没建）。
CREATE TABLE IF NOT EXISTS cred_credential (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
  cred_no         VARCHAR(64)  NOT NULL                COMMENT '凭据号，被 *.cred_ref 引用',
  realm           VARCHAR(16)  NOT NULL                COMMENT 'STAFF/AGENT',
  subject_no      VARCHAR(64)  NOT NULL                COMMENT '主体业务号：员工号 / 代理主体号',
  algo            VARCHAR(16)  NOT NULL DEFAULT 'BCRYPT' COMMENT 'BCRYPT',
  hash            VARCHAR(128) NOT NULL                COMMENT '口令散列。**绝不存明文，也绝不进日志**',
  must_change     TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '1=下次登录必须改密（建号发的一次性口令）',
  failed_count    INT          NOT NULL DEFAULT 0      COMMENT '连续失败次数，成功即清零',
  locked_until    DATETIME(3)  NULL                    COMMENT '锁定到期时刻；空=未锁定。锁定期内口令正确也拒',
  pwd_changed_at  DATETIME(3)  NULL                    COMMENT '上次改密时刻',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version         BIGINT       NOT NULL DEFAULT 0,
  deleted         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cred_no (cred_no),
  -- 一个主体在一个 realm 下只有一份凭据。没有这条约束的话，
  -- 同一个人可能挂着两份口令，改了其中一份而用另一份仍能登进来
  UNIQUE KEY uk_realm_subject (realm, subject_no),
  KEY idx_subject (subject_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录凭据（员工与代理共用，realm 区分）';

-- ── 二、员工身份列：按 hash 等值查 ──────────────────────────────────
-- iam_employee 今天存的是**明文** email / phone，没有 hash，而登录要按 email_hash 等值查。
-- 加列而不是另建 staff_principal 镜像 agt_principal：代理的一个自然人可以属于多个主体
-- （ADR-030，所以需要 principal 这一层），而员工与档案是一对一，多一张表换不来东西。
--
-- 形状照抄 agt_principal（varchar(64) 存 HMAC-SHA256 的 hex），但**可空**：
-- 存量员工里有大量没有邮箱/手机的行（测试库 200 个在职里 195 个两者皆空），
-- 建成 NOT NULL 会让这条迁移在那些库上直接失败。
ALTER TABLE iam_employee
  ADD COLUMN IF NOT EXISTS email_hash VARCHAR(64) NULL COMMENT 'HMAC-SHA256(lower(trim(邮箱)), pepper)，登录按它等值查',
  ADD COLUMN IF NOT EXISTS phone_hash VARCHAR(64) NULL COMMENT 'HMAC-SHA256(规范化手机号, pepper)，登录按它等值查',
  ADD COLUMN IF NOT EXISTS hash_ver   TINYINT(4)  NOT NULL DEFAULT 1 COMMENT 'pepper 版本。轮换后据它判断哪些行还没重算';

-- 登录路径的等值查，两列各一个索引。
-- **不建唯一索引**：可空列上的唯一索引在 MySQL 里对 NULL 不生效
-- （V49 的 uk_scope_target 就是这么「什么都没拦住」的），
-- 而 195 个 NULL 行会让它看起来建成功了、实际形同虚设。
-- 「一个邮箱只能属于一个员工」这条约束由写入侧保证，不靠索引。
CREATE INDEX idx_employee_email_hash ON iam_employee (email_hash);
CREATE INDEX idx_employee_phone_hash ON iam_employee (phone_hash);

-- ⚠️ **哈希回填不在这条迁移里**：它需要 identity-pepper（运行期配置），SQL 算不出来。
-- 由 staff-identity-backfill 任务做（可手动触发、幂等）。
-- 在回填跑完之前，这两列全是 NULL —— 不影响任何现有功能，因为还没有代码读它们。
