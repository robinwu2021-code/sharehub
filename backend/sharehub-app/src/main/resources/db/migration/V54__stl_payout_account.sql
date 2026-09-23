-- ============================================================
-- ShareHub · 收款账户：审批完的提现终于知道往哪打钱
--
-- 依据 ADR-029 §5.2 / TDD-运营主体与商户 V48（编号取 V54——V48 已被占用）。
--
-- 【在此之前这条链是断的】实测：
--   · loc_venue 没有任何账户列；
--   · agt_agent.settle_account 有列，但**全仓没有一行代码读它**；
--   · stl_withdrawal 只有 payee_type/payee_no，没有账户。
-- 结果是提现审核页能点「通过」，审批完不知道往哪打钱 —— 这卡在 L0「⑦ 分钱」的最后一步。
--
-- 【为什么运营主体与场地方共用一张表】
-- 两者都是分润受益方（Payee），都需要被打款；差别在**收款机制的另一侧**：
-- 运营主体还需要「商户」（进件、收单、子商户号），场地方从不与消费者发生支付关系，
-- 它只是周期性地被打款。所以「收款账户」共用，「商户」只挂运营主体（ADR-029 §3.1）。
-- ============================================================

CREATE TABLE IF NOT EXISTS stl_payout_account (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  account_no     VARCHAR(36)  NOT NULL                 COMMENT '业务键 PA*',
  payee_type     VARCHAR(16)  NOT NULL                 COMMENT 'OPERATOR 运营主体 / VENUE 场地方',
  payee_no       VARCHAR(36)  NOT NULL                 COMMENT 'agt_agent.agent_no 或 loc_venue.venue_no',
  bank_code      VARCHAR(32)  NOT NULL                 COMMENT '→ md_bank.bank_code',
  account_name   VARCHAR(128) NOT NULL                 COMMENT '户名；须与主体法人名一致，否则银行会退回',
  account_masked VARCHAR(64)  NOT NULL                 COMMENT 'IBAN 掩码；明文入 sharehub_pii。**掩码不可做等值判断**',
  currency       VARCHAR(8)   NOT NULL DEFAULT 'AED',
  is_default     TINYINT(1)   NOT NULL DEFAULT 1       COMMENT '同一受益方恰好一个默认账户（应用层保证，见下）',
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  tenant_id      VARCHAR(36)  NOT NULL DEFAULT 'MAIN'  COMMENT '历史遗留常量，恒 MAIN（ADR-026）',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by     VARCHAR(36)      NULL,
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by     VARCHAR(36)      NULL,
  version        BIGINT       NOT NULL DEFAULT 0,
  deleted        TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stl_payout_account_no (account_no),
  KEY idx_stl_payout_account_payee (payee_type, payee_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款账户（打款用；运营主体与场地方共用）';

-- ---------- 提现落账户快照 ----------
-- ⚠️ **stl_withdrawal 上已经有两列长得像收款账户，但都不是**，别重复加（我就差点加重）：
--   · account_no —— 是**出款**账户（acct_account.account_no），即「平台的钱从哪个账户出」；
--   · bank_code  —— 是收款银行（md_bank），**只有银行没有账号**，打不了款。
-- 真正缺的是「打到哪个账号、户名是谁」。
--
-- **引用 + 快照两者都要**，缺一不可：
--   · 只存引用：账户改了名或换了卡，历史提现单跟着变 —— 对不上当初的打款回单；
--   · 只存快照：查不到「这笔打给的是哪条账户记录」，账户被停用时也没法回溯影响面。
-- 本仓既有的快照原则（领域模型 §2.3）：单据落快照，关联可换。
--
-- bank_code 不再重复一份：它本来就是提现单自己的列，申请时填入、之后不随账户变，
-- 已经是快照语义了。
ALTER TABLE stl_withdrawal
  ADD COLUMN IF NOT EXISTS payout_account_no     VARCHAR(36)  NULL COMMENT '→ stl_payout_account.account_no（收款方账户）；审批通过时落定',
  ADD COLUMN IF NOT EXISTS payout_account_name   VARCHAR(128) NULL COMMENT '快照：审批那一刻的户名',
  ADD COLUMN IF NOT EXISTS payout_account_masked VARCHAR(64)  NULL COMMENT '快照：账号掩码';

-- ---------- 场地方 ↔ 运营主体：同一法人 ----------
ALTER TABLE loc_venue
  ADD COLUMN IF NOT EXISTS operator_no VARCHAR(36) NULL
  COMMENT '同一法人：本场地方同时是这个运营主体（商场自投自营）；NULL = 纯场地方';
ALTER TABLE loc_venue
  ADD UNIQUE KEY IF NOT EXISTS uk_venue_operator (operator_no);

-- ============================================================
-- 【这里的可空唯一键是对的，与 share_record 那个缺陷不是一回事】
--
-- share_record 需要「每个 NULL 组合也只能有一行」—— 而 MySQL/MariaDB 的 UNIQUE 不约束 NULL，
-- 于是幂等闸门失效。
-- 这里恰恰**需要**「多个场地方都可以没有关联主体」，同时「一个主体最多被一个场地方认领」。
--
-- 判据：唯一键里的可空列，是「允许多行为空」还是「每个空值组合也要唯一」？
--       前者可空是对的，后者必须 NOT NULL（或用生成列，见 agt_apply.active_key）。
--
-- 【is_default 为什么不做成唯一键】
-- 想表达的是「同一受益方恰好一个默认」，写成 UNIQUE(payee_type, payee_no, is_default)
-- 会把「恰好一个非默认」也一起约束掉 —— 一个受益方就只能有两个账户了。
-- 这条留给应用层：切换默认时在同一事务里把旧的置 0。
-- ============================================================
