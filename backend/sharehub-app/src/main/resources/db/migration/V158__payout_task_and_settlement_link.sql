-- 商家一期 B4（需求方案 D8 / D12 · §八，优化方案 §2.5）：平台运营端付款任务。
--
-- 一期提现 = 平台向商家付款，由财务在运营端手动发起，一般三个月一次：
--   选付款截止日 → 预览 → 生成付款任务（每个商家合并一笔提现单）→ 复核 → 导出转账文件
--   → 线下银行转账 → 批量确认到账（逐笔可标失败）→ 结算单置已付 + 记账。
-- 不接支付渠道，只做记账。
--
-- 写法：不用 MariaDB 的 `ADD COLUMN IF NOT EXISTS`，本机 MySQL 9.7 与 MariaDB 都能跑。

CREATE TABLE IF NOT EXISTS stl_payout_task (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_no        VARCHAR(36)   NOT NULL                 COMMENT '业务键 PT*',
  cutoff_date    DATE          NOT NULL                 COMMENT '付款截止日：账期结束日不晚于它的已确认结算单纳入本任务',
  currency       VARCHAR(8)    NOT NULL DEFAULT 'AED',
  item_count     INT           NOT NULL DEFAULT 0       COMMENT '提现单笔数（每个商家一笔）',
  total_amount   DECIMAL(18,2) NOT NULL DEFAULT 0,
  status         VARCHAR(24)   NOT NULL                 COMMENT '付款任务状态词表：PENDING_REVIEW/PENDING_TRANSFER/DONE/CANCELLED',
  created_by     VARCHAR(36)       NULL                 COMMENT '发起人',
  created_name   VARCHAR(64)       NULL,
  reviewed_by    VARCHAR(36)       NULL                 COMMENT '复核人；不得与发起人相同',
  reviewed_name  VARCHAR(64)       NULL,
  reviewed_at    DATETIME(3)       NULL,
  reject_reason  VARCHAR(512)      NULL                 COMMENT '复核驳回原因；驳回即整任务撤回',
  confirmed_by   VARCHAR(36)       NULL                 COMMENT '最后一次确认到账的人',
  confirmed_at   DATETIME(3)       NULL,
  bank_batch_ref VARCHAR(64)       NULL                 COMMENT '银行批次参考号（整批确认时填）',
  transfer_date  DATE              NULL                 COMMENT '银行转账日期',
  export_count   INT           NOT NULL DEFAULT 0       COMMENT '转账文件导出次数（每次导出另记 stl_payout_export）',
  tenant_id      VARCHAR(36)   NOT NULL DEFAULT 'MAIN'  COMMENT '历史遗留常量，恒 MAIN（ADR-026）',
  created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by     VARCHAR(36)       NULL,
  version        BIGINT        NOT NULL DEFAULT 0,
  deleted        TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stl_payout_task_no (task_no),
  KEY idx_stl_payout_task_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='付款任务（平台向商家付款的批次）';

-- 导出留痕：文件含完整 IBAN，谁在什么时候导出过、导出的是哪一版，要查得到
CREATE TABLE IF NOT EXISTS stl_payout_export (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_no      VARCHAR(36)  NOT NULL,
  format       VARCHAR(8)   NOT NULL                 COMMENT '导出格式词表：CSV/XLSX',
  file_sha256  CHAR(64)     NOT NULL                 COMMENT '文件摘要：与银行网银里上传的那份对得上',
  exported_by  VARCHAR(36)      NULL,
  exported_name VARCHAR(64)     NULL,
  exported_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  PRIMARY KEY (id),
  KEY idx_stl_payout_export_task (task_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='付款任务转账文件导出记录（append）';

ALTER TABLE stl_withdrawal
  ADD COLUMN task_no VARCHAR(36) NULL COMMENT '所属付款任务；空 = 商家自助申请的提现（一期不开放）' AFTER withdraw_no,
  ADD COLUMN origin  VARCHAR(16) NULL COMMENT '提现来源词表：APPLY/PAYOUT_TASK；空 = 本迁移前的单，按 APPLY 处理' AFTER task_no,
  ADD KEY idx_stl_withdrawal_task (task_no);

-- 结算单 → 提现单：非空即「在途或已付」，不会再被新任务纳入。
-- 用一列而不是关联表：一张结算单同一时刻至多属于一张在途提现单，一列就能表达，「已纳入」的判断也只是一个等值条件。
-- 提现失败或任务被驳回时清空，结算单回到可付款范围。
ALTER TABLE stl_settlement
  ADD COLUMN withdraw_no VARCHAR(36) NULL COMMENT '纳入的提现单；非空 = 在途或已付，失败/撤回时清空' AFTER status,
  ADD KEY idx_stl_settlement_withdraw (withdraw_no);

-- 收款账户：完整 IBAN 的密文（AES-256-GCM，sharehub.security.pii-key）。
-- 此前只存掩码，付款任务导出银行转账文件时拿不到原文。空 = 存量或未配密钥时录入，需补录。
ALTER TABLE stl_payout_account
  ADD COLUMN account_enc VARBINARY(256) NULL COMMENT '完整账号（IBAN）密文；导出转账文件时解密。空 = 需补录' AFTER account_masked;
