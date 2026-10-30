-- 执行计划S01：兼容扩展，不回填猜测的历史业务事实。
SET NAMES utf8mb4;

CREATE TABLE inv_receipt_source (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  source_system VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  external_receipt_ref VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  source_line_ref VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  purchase_no VARCHAR(36) NULL,
  purchase_line_no INT NULL,
  intake_no VARCHAR(36) NOT NULL,
  received_qty INT NOT NULL,
  received_at DATETIME(3) NULL,
  payload_hash CHAR(64) COLLATE ascii_bin NOT NULL,
  operation_no VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by VARCHAR(36) NULL,
  updated_by VARCHAR(36) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_inv_receipt_source (tenant_id, source_system, external_receipt_ref, source_line_ref),
  KEY idx_inv_receipt_purchase (purchase_no, purchase_line_no),
  KEY idx_inv_receipt_intake (intake_no),
  CONSTRAINT chk_inv_receipt_qty CHECK (received_qty > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购实收及来源防重；不等同质检放行';
ALTER TABLE inv_intake
  ADD COLUMN purchase_no VARCHAR(36) NULL,
  ADD COLUMN operation_no VARCHAR(64) NULL,
  ADD COLUMN opening_basis VARCHAR(1024) NULL,
  ADD COLUMN source_type VARCHAR(32) NULL,
  ADD COLUMN invoice_missing_reason VARCHAR(512) NULL,
  ADD COLUMN valuation_basis VARCHAR(1024) NULL,
  ADD COLUMN opening_value DECIMAL(18,4) NULL,
  ADD COLUMN review_ref VARCHAR(64) NULL,
  ADD KEY idx_intake_purchase (purchase_no),
  ADD KEY idx_intake_operation (operation_no);
ALTER TABLE inv_intake_item ADD COLUMN purchase_line_no INT NULL;
