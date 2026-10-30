-- 执行计划S01：兼容扩展，不回填猜测的历史业务事实。
SET NAMES utf8mb4;

CREATE TABLE inv_purchase (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  purchase_no VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  supplier_no VARCHAR(36) NOT NULL,
  purchase_date DATE NOT NULL,
  supplier_invoice_no VARCHAR(128) NULL,
  currency VARCHAR(8) NOT NULL,
  net_amount DECIMAL(18,4) NULL,
  vat_amount DECIMAL(18,4) NULL,
  gross_amount DECIMAL(18,4) NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / ORDERED / PART_RECEIVED / RECEIVED / CLOSED / CANCELLED',
  expected_at DATE NULL,
  note VARCHAR(1024) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by VARCHAR(36) NULL,
  updated_by VARCHAR(36) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_inv_purchase_no (purchase_no),
  KEY idx_inv_purchase_supplier (tenant_id, supplier_no, purchase_date),
  KEY idx_inv_purchase_status (tenant_id, status, purchase_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购登记；不改变实物库存';
CREATE TABLE inv_purchase_line (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  purchase_no VARCHAR(36) NOT NULL,
  line_no INT NOT NULL,
  item_type VARCHAR(24) NOT NULL COMMENT 'CABINET / POWERBANK / MATERIAL',
  material_no VARCHAR(36) NULL,
  model VARCHAR(128) NULL,
  ordered_qty INT NOT NULL,
  unit_cost DECIMAL(18,4) NOT NULL,
  vat_rate DECIMAL(8,4) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by VARCHAR(36) NULL,
  updated_by VARCHAR(36) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_inv_purchase_line (purchase_no, line_no),
  CONSTRAINT chk_inv_purchase_ordered CHECK (ordered_qty > 0),
  CONSTRAINT chk_inv_purchase_cost CHECK (unit_cost >= 0),
  CONSTRAINT chk_inv_purchase_vat CHECK (vat_rate IS NULL OR (vat_rate >= 0 AND vat_rate <= 100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购行；实收由来源收货记录计算';
