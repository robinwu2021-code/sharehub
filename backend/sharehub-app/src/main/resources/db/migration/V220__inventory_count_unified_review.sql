-- 盘点业务与统一审批的关联和执行恢复；不保存流程步骤，不另建库存余额。
SET NAMES utf8mb4;
CREATE TABLE inv_count_review (
  count_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  warehouse_no VARCHAR(36) NOT NULL,
  round_no INT NOT NULL DEFAULT 0,
  execution_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PREPARING / SUBMITTING / IN_REVIEW / SUBMIT_FAILED / DECIDED / APPLY_FAILED / APPLIED',
  content_version INT NULL,
  content_hash CHAR(64) COLLATE ascii_bin NULL,
  submitter_no VARCHAR(36) NULL,
  submitter_name VARCHAR(128) NULL,
  scope_region_id VARCHAR(36) NULL,
  approval_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
  instance_no VARCHAR(64) NULL,
  decision VARCHAR(16) NULL COMMENT 'APPROVED / REJECTED / WITHDRAWN',
  decider_no VARCHAR(36) NULL,
  decider_name VARCHAR(128) NULL,
  decision_note VARCHAR(512) NULL,
  last_error VARCHAR(1024) NULL,
  gain_inbound_no VARCHAR(128) NULL,
  loss_outbound_no VARCHAR(128) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_count_review_approval (approval_no),
  CONSTRAINT chk_count_review_execution CHECK (BINARY execution_state IN ('DRAFT','PREPARING','SUBMITTING','IN_REVIEW','SUBMIT_FAILED','DECIDED','APPLY_FAILED','APPLIED')),
  CONSTRAINT chk_count_review_decision CHECK (decision IS NULL OR BINARY decision IN ('APPROVED','REJECTED','WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点统一审批关联及库存执行恢复';
INSERT INTO iam_permission(code,name,module) VALUES
 ('device:inventory:audit','库存管理 · 库存审核','device')
ON DUPLICATE KEY UPDATE name=VALUES(name), module=VALUES(module);
