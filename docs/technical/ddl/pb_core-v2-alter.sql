-- ============================================================
-- powerbank · pb_core v2 存量表改造（ALTER）+ pb_pii / pb_auth 正式建库
-- 对齐 db-design.md v2 §12.2「改造 6 表」+ §七/§八
-- MySQL 8 · InnoDB · utf8mb4_0900_ai_ci
--
-- 执行顺序：先跑 4 个 v1 脚本 → 再跑 3 个 v2 新表脚本 → 最后跑本脚本。
-- 幂等性：MySQL 8 的 ALTER TABLE 无 IF NOT EXISTS COLUMN 语法，重复执行会报
--        1060 Duplicate column name。请用迁移工具（Flyway/Liquibase）管控版本，
--        或先查 information_schema.COLUMNS 再决定是否执行。
-- ============================================================
SET NAMES utf8mb4;
USE pb_core;   -- 本脚本第 6/7 段会切到 pb_pii/pb_auth，第 8 段再切回；此处先锚定

-- ============================================================
-- 1) dev_alert → dev_alarm（更名 + 扩列）
--    新表建在 pb_core-v2-ops-alarm.sql；此处只做数据迁移与旧表退场。
--    ⚠️ 先确认 dev_alarm 已建、且 dev_alert 数据已核对，再取消注释执行。
-- ============================================================
-- INSERT INTO dev_alarm
--   (alarm_no, tenant_id, cabinet_no, source, level, alarm_code, message,
--    status, dedup_key, count, occurred_at, created_at, updated_at)
-- SELECT
--   CONCAT('ALM', LPAD(id, 6, '0')),   -- 旧表无业务键，按 id 补生成
--   tenant_id, cabinet_no, source, severity, code, message,
--   status, dedup_key, count, created_at, created_at, updated_at
-- FROM dev_alert;
--
-- RENAME TABLE dev_alert TO dev_alert_deprecated_v1;   -- 保留一个版本周期再 DROP


-- ============================================================
-- 2) ord_rent：免费订单 + 用券 + 展示名冗余
--    「免费订单」不建独立表，用 free_reason IS NOT NULL 筛选（db-design §5.1）
-- ============================================================
ALTER TABLE ord_rent
  ADD COLUMN coupon_no      VARCHAR(36)      NULL COMMENT '结算所用券(逻辑引用 usr_coupon)'          AFTER price_plan_no,
  ADD COLUMN location_no    VARCHAR(36)      NULL COMMENT '借出点位(逻辑引用 loc_location)'          AFTER site_no,
  ADD COLUMN location_name  VARCHAR(128)     NULL COMMENT '点位名快照(冗余·不随源改名回溯)'          AFTER location_no,
  ADD COLUMN buyout         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否买断转持有'                 AFTER deposit_amount,
  ADD COLUMN free_reason    VARCHAR(24)      NULL COMMENT '免费单原因(空=正常单)：INTERNAL_TEST/VIP/BD_DEMO/MERCHANT_SELF，源 usr_free_whitelist.reason',
  ADD COLUMN waived_amount  DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '减免金额(免费单统计口径)';

-- 数据范围热路径 + 列表默认排序一次走完（db-design §十）
ALTER TABLE ord_rent
  ADD KEY idx_ord_scope     (tenant_id, agent_no, status, created_at),
  ADD KEY idx_ord_owner     (tenant_id, c_user_no, created_at),
  ADD KEY idx_ord_free      (tenant_id, free_reason, created_at);


-- ============================================================
-- 3) usr_credit：补风控字段；blacklisted 迁出到独立表 usr_blacklist
--    （独立表需要 released_at / released_by 做解除留痕，单列布尔存不下）
-- ============================================================
ALTER TABLE usr_credit
  ADD COLUMN risk_no    VARCHAR(36)  NULL COMMENT '业务键 RK*'                       AFTER id,
  ADD COLUMN risk_level VARCHAR(8)   NULL COMMENT 'HIGH/MEDIUM/LOW'                  AFTER score,
  ADD COLUMN flagged_at DATETIME(3)  NULL COMMENT '标记时间'                          AFTER reason,
  ADD COLUMN created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD UNIQUE KEY uk_credit_risk_no (risk_no),
  ADD KEY idx_credit_level (tenant_id, risk_level);

-- 存量黑名单迁到 usr_blacklist（该表建在 pb_core-v2-trade-user.sql）
-- INSERT INTO usr_blacklist (blacklist_no, tenant_id, c_user_no, reason, blacklisted_at, status)
-- SELECT CONCAT('BL', LPAD(id, 4, '0')), tenant_id, c_user_no, reason, updated_at, 'ACTIVE'
-- FROM usr_credit WHERE blacklisted = 1;
--
-- 迁完再删列（保留一个版本周期，双写期结束后执行）：
-- ALTER TABLE usr_credit DROP COLUMN blacklisted;


-- ============================================================
-- 4) stl_withdrawal：资金审批合规四件套 + 收款方
--    手续费口径唯一来源 = sys_biz_rule(category='WITHDRAW').rule.feeRate/feeCap
--    「实际到账」= amount - fee，派生不落库
-- ============================================================
ALTER TABLE stl_withdrawal
  ADD COLUMN payee_name    VARCHAR(128)     NULL COMMENT '收款方名快照(冗余)'                 AFTER payee_no,
  ADD COLUMN account_no    VARCHAR(36)      NULL COMMENT '账户(逻辑引用 acct_account)'        AFTER payee_name,
  ADD COLUMN bank_code     VARCHAR(32)      NULL COMMENT '收款银行(逻辑引用 md_bank)'          AFTER account_no,
  ADD COLUMN fee           DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '提现手续费(取 sys_biz_rule WITHDRAW)' AFTER amount,
  ADD COLUMN applicant_no  VARCHAR(36)      NULL COMMENT '申请人(代理账号/员工)'                AFTER applied_at,
  ADD COLUMN auditor_no    VARCHAR(36)      NULL COMMENT '审批人(服务端回填,不信前端)',
  ADD COLUMN auditor_name  VARCHAR(64)      NULL COMMENT '审批人名快照',
  ADD COLUMN audited_at    DATETIME(3)      NULL COMMENT '审批时间(NULL=未审)',
  ADD COLUMN reject_reason VARCHAR(256)     NULL COMMENT '驳回原因(驳回时必填)',
  ADD COLUMN deleted       TINYINT(1)   NOT NULL DEFAULT 0;

ALTER TABLE stl_withdrawal
  ADD KEY idx_wd_audit (tenant_id, status, applied_at);


-- ============================================================
-- 5) pay_order：支付场景扩到充值/会员 + 渠道
-- ============================================================
ALTER TABLE pay_order
  MODIFY COLUMN type VARCHAR(16) NOT NULL COMMENT 'DEPOSIT/RENT/BUYOUT/RECHARGE/MEMBERSHIP',
  ADD COLUMN channel_code VARCHAR(32) NULL COMMENT '支付渠道(逻辑引用 pay_channel)' AFTER currency;

-- order_no 对充值/会员场景可空（不挂租借订单）
ALTER TABLE pay_order
  MODIFY COLUMN order_no VARCHAR(36) NULL COMMENT '关联租借订单；RECHARGE/MEMBERSHIP 场景为空';

ALTER TABLE pay_order
  ADD KEY idx_pay_owner (tenant_id, c_user_no, created_at);


-- ============================================================
-- 6) pb_pii（独立库 + 独立 KMS + 区域驻留 · PDPL 硬要求 ADR-009）
--    v2 变更：pii_user 从「仅 C 端」扩为承载 员工/场地方/代理 联系人
--    业务表只留掩码值供列表直出，明文只在本库，经脱敏接口访问。
-- ============================================================
CREATE DATABASE IF NOT EXISTS pb_pii DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE pb_pii;

CREATE TABLE IF NOT EXISTS pii_user (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  subject_type  VARCHAR(16)  NOT NULL                COMMENT 'C_USER/EMPLOYEE/VENUE/AGENT',
  subject_no    VARCHAR(36)  NOT NULL                COMMENT '主体业务键(c_user_no/employee_no/venue_no/agent_no)',
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id     VARCHAR(36)      NULL                COMMENT '数据驻留区域',
  phone_enc     VARBINARY(512)   NULL                COMMENT '[KMS] 手机号密文',
  phone_hash    CHAR(64)         NULL                COMMENT 'HMAC-SHA256，用于按手机号检索(不可逆)',
  real_name_enc VARBINARY(512)   NULL                COMMENT '[KMS] 真实姓名密文',
  id_no_enc     VARBINARY(512)   NULL                COMMENT '[KMS] 证件号密文',
  id_no_hash    CHAR(64)         NULL                COMMENT 'HMAC-SHA256，用于去重',
  email_enc     VARBINARY(512)   NULL                COMMENT '[KMS] 邮箱密文',
  kms_key_id    VARCHAR(64)      NULL                COMMENT '加密所用 KMS 主密钥版本(轮换用)',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  purged_at     DATETIME(3)      NULL                COMMENT '注销清除时间(usr_logoff 到期后回填)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pii_subject (subject_type, subject_no),
  KEY idx_pii_phone_hash (phone_hash),
  KEY idx_pii_idno_hash (id_no_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人数据(独立KMS·区域驻留·PDPL)';


-- ============================================================
-- 7) pb_auth（独立库 + 独立 KMS · 仅 neargo-auth-core 访问）
--    v2 变更：realm 三池 —— STAFF(员工) / AGENT(代理账号) / CONSUMER(C端)
--    结构最终由 auth-core 定义，此处为对齐用骨架。
-- ============================================================
CREATE DATABASE IF NOT EXISTS pb_auth DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE pb_auth;

CREATE TABLE IF NOT EXISTS cred_credential (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  cred_no        VARCHAR(36)  NOT NULL,
  realm          VARCHAR(16)  NOT NULL                COMMENT 'STAFF/AGENT/CONSUMER',
  tenant_id      VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  subject_no     VARCHAR(36)  NOT NULL                COMMENT '业务侧主体(employee_no/account_no/c_user_no)',
  login_name     VARCHAR(128)     NULL                COMMENT '登录名(员工)',
  phone_hash     CHAR(64)         NULL                COMMENT '手机哈希(C端/代理登录)',
  password_hash  VARCHAR(256)     NULL                COMMENT 'Argon2id',
  mfa_secret_enc VARBINARY(256)   NULL                COMMENT '[KMS] MFA 种子',
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/LOCKED/DISABLED',
  fail_count     INT          NOT NULL DEFAULT 0,
  locked_until   DATETIME(3)      NULL,
  last_login_at  DATETIME(3)      NULL,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cred_no (cred_no),
  UNIQUE KEY uk_cred_realm_subject (realm, tenant_id, subject_no),
  UNIQUE KEY uk_cred_realm_login (realm, tenant_id, login_name),
  KEY idx_cred_phone (realm, tenant_id, phone_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录凭据(独立KMS·仅 auth-core 访问)';

-- 业务域只持 subject_no 逻辑引用，不落任何凭据字段。


-- ============================================================
-- 8) 状态机定稿落库（db-design §九·A · 2026-07-29 业务梳理）
-- ⚠️ 上面第 6/7 段把会话切到了 pb_pii / pb_auth，这里必须切回 pb_core，
--    否则以下 ALTER 会打到错误的库（或直接报表不存在）。
-- ============================================================
USE pb_core;

-- 8.1 dev_powerbank：两套不兼容枚举合一为 7 态，并补买断终态 SOLD
--     废弃 DEPLOYED(=IN_CABINET) / RETURNED(是事件不是状态) / RETIRED(=SCRAP)
--     位置由 cabinet_no + slot_index 表达，不再用状态位重复表达
ALTER TABLE dev_powerbank
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'IN_STOCK'
    COMMENT 'IN_STOCK 入库未投放/IN_CABINET 在仓可借/RENTED 借出中/FAULT 故障待修/LOST 丢失待追偿(半终态可回收)/SOLD 买断(终态)/SCRAP 报废(终态)',
  ADD COLUMN cycles INT NOT NULL DEFAULT 0 COMMENT '循环次数(健康度)'          AFTER battery,
  ADD COLUMN health VARCHAR(8) NOT NULL DEFAULT 'OK' COMMENT 'OK/FAULT'      AFTER cycles;

-- 存量状态迁移（IN_USE 是 v1 值，对应新的 RENTED）
UPDATE dev_powerbank SET status = 'IN_CABINET' WHERE status IN ('DEPLOYED', 'RETURNED');
UPDATE dev_powerbank SET status = 'RENTED'     WHERE status = 'IN_USE';
UPDATE dev_powerbank SET status = 'SCRAP'      WHERE status = 'RETIRED';
-- ⚠️ SOLD 无法从存量推导（v1 没有买断终态）：历史买断单需按
--    ord_rent.buyout = 1 回填，核对后再执行：
-- UPDATE dev_powerbank p JOIN ord_rent o ON o.powerbank_no = p.powerbank_no
--   SET p.status = 'SOLD' WHERE o.buyout = 1 AND p.status = 'LOST';

-- 8.2 dev_cabinet：补 IN_STOCK（未投放）——「库存调拨」需要区分仓库机柜与在投机柜
--     online_status 是正交轴（通信可达性），不并入 status
ALTER TABLE dev_cabinet
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'IN_STOCK'
    COMMENT 'IN_STOCK 入库未投放(location_no 空)/DEPLOYED 已投放/FAULT 故障停用/RETIRED 退役(终态)';

-- 已挂点位的机柜必然是已投放，回填保证语义自洽
UPDATE dev_cabinet SET status = 'DEPLOYED'
  WHERE status = 'IN_STOCK' AND location_no IS NOT NULL AND location_no <> '';

-- 8.3 wo_order：补 close_reason —— 告警误报开的单需要「无效关闭」，
--     但不新增 CANCELLED 态（避免多一条平行终态线）
ALTER TABLE wo_order
  ADD COLUMN close_reason VARCHAR(16) NULL
    COMMENT '关单原因(status=CLOSED 时必填)：RESOLVED 正常完结/INVALID 误报/DUPLICATE 重复单/WITHDRAWN 撤单',
  ADD COLUMN closed_at    DATETIME(3) NULL COMMENT '关单时间',
  ADD COLUMN audited_by   VARCHAR(36) NULL COMMENT '验收人(AUDITED 时回填,服务端写)',
  ADD COLUMN audited_at   DATETIME(3) NULL COMMENT '验收时间';

UPDATE wo_order SET close_reason = 'RESOLVED' WHERE status = 'CLOSED' AND close_reason IS NULL;

-- 8.4 wo_sla_rule：wo_type 由自由字符串收敛为 WorkOrderType 枚举（db-design §十三.7）
ALTER TABLE wo_sla_rule
  MODIFY COLUMN wo_type VARCHAR(16) NOT NULL
    COMMENT 'FAULT/REFILL/INSPECT/INSTALL/REMOVE/COMPLAINT/CLEAN';
