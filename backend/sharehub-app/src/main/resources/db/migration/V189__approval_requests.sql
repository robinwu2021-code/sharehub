-- ============================================================
-- V189 客服补偿 / 免单与手工记账凭证的审批（运营方原始需求 SEC-03，第 6 批，2026-10-29）
--
-- 用户定：阈值可配，缺省关（= 现行：补偿与免单即时生效、手工凭证即时入账）。
--   · approval.compensation.threshold —— 补偿金额 / 免单涉及的应收 ≥ 它就先审后生效；空 = 不需要审批
--   · approval.voucher.threshold      —— 手工凭证借方合计 ≥ 它就先审后入账；空 = 不需要审批
-- 审批人不得是申请人（四眼）。补偿 / 免单由持 order:refund:audit 的人审（动钱的归财务），
-- 凭证由另一位持 finance:ledger:create 的人审 —— 都是既有权限码，不新增。
--
-- ops_approval_request：一张待办一行，原请求整份存 payload，批准时按它重放原动作（重放前照常校验，
-- 订单状态在等待期间变了就批准失败、如实告诉审批人）。
-- 本文件只用 MySQL 与 MariaDB 都认的语法。
-- ============================================================
CREATE TABLE IF NOT EXISTS ops_approval_request (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  request_no     VARCHAR(36)   NOT NULL COMMENT 'AP-YYYYMMDD-xxxx',
  tenant_id      VARCHAR(36)   NOT NULL DEFAULT 'MAIN',
  kind           VARCHAR(24)   NOT NULL COMMENT 'ORDER_COMPENSATE/ORDER_WAIVE/LEDGER_VOUCHER',
  ref_no         VARCHAR(36)       NULL COMMENT '订单号；凭证类为空（批准后回填凭证号到 result_ref）',
  amount         DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '触发审批的金额',
  currency       VARCHAR(8)    NOT NULL DEFAULT 'AED',
  reason         VARCHAR(256)      NULL COMMENT '申请原因（干预原因 / 凭证摘要）',
  payload        TEXT          NOT NULL COMMENT '原请求体 JSON，批准时重放',
  status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/FAILED',
  applicant_no   VARCHAR(64)       NULL,
  applicant_name VARCHAR(64)       NULL,
  approver_no    VARCHAR(64)       NULL,
  approver_name  VARCHAR(64)       NULL,
  decided_at     DATETIME(3)       NULL,
  decide_note    VARCHAR(256)      NULL COMMENT '驳回原因 / 重放失败原因',
  result_ref     VARCHAR(64)       NULL COMMENT '批准后产物：干预单号 / 凭证号',
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by     VARCHAR(64)       NULL,
  updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by     VARCHAR(64)       NULL,
  version        BIGINT        NOT NULL DEFAULT 0,
  deleted        TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_oar_no (tenant_id, request_no),
  KEY idx_oar_status (tenant_id, status, kind, created_at),
  KEY idx_oar_ref (ref_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待审批的资金操作（客服补偿 / 免单 / 手工凭证）';

INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'approval.compensation.threshold', '客服补偿 / 免单 ≥ 此金额需财务审批后生效；空 = 不需要审批（现行）', '', '财务'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'approval.compensation.threshold');
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name)
SELECT 'MAIN', 'approval.voucher.threshold', '手工记账凭证借方合计 ≥ 此金额需另一位财务审批后入账；空 = 不需要审批（现行）', '', '财务'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'approval.voucher.threshold');
