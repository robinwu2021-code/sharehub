-- ============================================================
-- ShareHub · 三张表的注释在库里是乱码（双重编码）
--
-- 【事实】test 库里 1 个表注释 + 11 个列注释读出来是
-- `æ”¶æ¬¾è´¦æˆ·` 这样的形态 —— 即 UTF-8 字节被当成 cp1252 读了一遍再编回 UTF-8。
-- 集中在 loc_venue(1) · stl_payout_account(8) · stl_withdrawal(3)。
--
-- 【不是迁移文件的问题】V54__stl_payout_account.sql 本身是干净的 UTF-8
-- （逐字节验过），所以是**执行那一刻的连接字符集**不对。
-- 已应用的迁移不能改（Flyway 校验和），因此用新迁移把注释重写一遍。
--
-- 【为什么不是「几个字看不懂」】
-- 列注释在本仓库是**词表的裁定依据**（见 known-undocumented-status-columns.txt
-- 与 StoredValueInVocabularyTest）。这里恰好有一条是真词表：
--   stl_payout_account.payee_type = 'OPERATOR 运营主体 / VENUE 场地方'
-- 乱码之后，下一个要判「这一列能填什么」的人读到的是一串问号。
-- 更直接的代价：db-schema-reference.md 是从这些注释生成的，
-- **一份带乱码的参考文档，下一个人会照着把乱码抄进代码注释里**。
--
-- 【正确文本从哪来】把乱码按 cp1252 → UTF-8 反向还原，
-- 逐条与 V54 等迁移文件里的原文**对照过**，一字不差。
--
-- 【MODIFY COLUMN 的写法】每一行的定义都是从 `SHOW CREATE TABLE` 原样抄的，
-- 只替换了 COMMENT。手工重写类型/可空/默认值极易出错 ——
-- 本次第一版就把「没有默认值」写成了 DEFAULT '\0NULL'、把 'MAIN' 写成 ''MAIN''，
-- 照那个版本执行会静默改掉两列的默认值。
-- 前提：各环境的这几列定义与 test 库一致（同一套迁移跑出来的）。
-- ============================================================
SET NAMES utf8mb4;

-- loc_venue
ALTER TABLE loc_venue MODIFY COLUMN `operator_no` varchar(36) DEFAULT NULL COMMENT '同一法人：本场地方同时是这个运营主体（商场自投自营）；NULL = 纯场地方';

-- stl_payout_account
ALTER TABLE stl_payout_account MODIFY COLUMN `account_no` varchar(36) NOT NULL COMMENT '业务键 PA*';
ALTER TABLE stl_payout_account MODIFY COLUMN `payee_type` varchar(16) NOT NULL COMMENT 'OPERATOR 运营主体 / VENUE 场地方';
ALTER TABLE stl_payout_account MODIFY COLUMN `bank_code` varchar(32) NOT NULL COMMENT '→ md_bank.bank_code';
ALTER TABLE stl_payout_account MODIFY COLUMN `account_name` varchar(128) NOT NULL COMMENT '户名；须与主体法人名一致，否则银行会退回';
ALTER TABLE stl_payout_account MODIFY COLUMN `account_masked` varchar(64) NOT NULL COMMENT 'IBAN 掩码；明文入 sharehub_pii。**掩码不可做等值判断**';
ALTER TABLE stl_payout_account MODIFY COLUMN `is_default` tinyint(1) NOT NULL DEFAULT 1 COMMENT '同一受益方恰好一个默认账户（应用层保证，见下）';
ALTER TABLE stl_payout_account MODIFY COLUMN `tenant_id` varchar(36) NOT NULL DEFAULT 'MAIN' COMMENT '历史遗留常量，恒 MAIN（ADR-026）';

-- stl_withdrawal
ALTER TABLE stl_withdrawal MODIFY COLUMN `payout_account_no` varchar(36) DEFAULT NULL COMMENT '→ stl_payout_account.account_no；审批通过时落定';
ALTER TABLE stl_withdrawal MODIFY COLUMN `payout_account_name` varchar(128) DEFAULT NULL COMMENT '快照：户名';
ALTER TABLE stl_withdrawal MODIFY COLUMN `payout_account_masked` varchar(64) DEFAULT NULL COMMENT '快照：账号掩码';

ALTER TABLE stl_payout_account COMMENT = '收款账户（打款用；运营主体与场地方共用）';
