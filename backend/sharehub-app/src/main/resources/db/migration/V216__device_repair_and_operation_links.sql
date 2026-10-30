-- 执行计划S01：兼容扩展，不回填猜测的历史业务事实。
SET NAMES utf8mb4;

CREATE TABLE dev_repair_detail (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  wo_no VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  cabinet_no VARCHAR(36) NOT NULL,
  repair_mode VARCHAR(24) NOT NULL COMMENT 'ONSITE / WAREHOUSE / VENDOR',
  supplier_no VARCHAR(36) NULL,
  warranty_ref VARCHAR(128) NULL,
  sent_at DATETIME(3) NULL,
  expected_return_at DATETIME(3) NULL,
  returned_at DATETIME(3) NULL,
  test_result VARCHAR(16) NULL COMMENT 'PASS / FAIL',
  accepted_by VARCHAR(36) NULL,
  accepted_at DATETIME(3) NULL,
  note VARCHAR(1024) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by VARCHAR(36) NULL,
  updated_by VARCHAR(36) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_dev_repair_wo (wo_no),
  KEY idx_dev_repair_cabinet (cabinet_no, created_at),
  KEY idx_dev_repair_return (repair_mode, expected_return_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='复用故障工单的维修明细；配件及附件走现有单据引用';
ALTER TABLE inv_transfer ADD COLUMN operation_no VARCHAR(64) NULL, ADD COLUMN reason VARCHAR(512) NULL;
ALTER TABLE inv_disposal ADD COLUMN operation_no VARCHAR(64) NULL;
ALTER TABLE inv_material_doc ADD COLUMN operation_no VARCHAR(64) NULL;
ALTER TABLE dev_movement_log ADD COLUMN delivery_no VARCHAR(36) NULL, ADD COLUMN repair_wo_no VARCHAR(36) NULL;
