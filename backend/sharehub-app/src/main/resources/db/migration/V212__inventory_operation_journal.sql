-- 执行计划S01：兼容扩展，不回填猜测的历史业务事实。
SET NAMES utf8mb4;

CREATE TABLE inv_operation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  operation_no VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  inventory_owner_ref VARCHAR(64) NOT NULL DEFAULT '',
  request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  payload_hash CHAR(64) COLLATE ascii_bin NOT NULL,
  payload LONGTEXT NULL COMMENT '恢复所需业务请求；禁止存令牌与密钥',
  engine VARCHAR(16) NOT NULL COMMENT 'LOCAL / SERVICE；创建后固定',
  kind VARCHAR(32) NOT NULL COMMENT '业务操作类别，由应用服务白名单约束',
  biz_ref VARCHAR(64) NULL,
  phase VARCHAR(32) NOT NULL DEFAULT 'PREPARED' COMMENT 'PREPARED / OUT_POSTED / IN_POSTED / COMPLETED / NEEDS_REVIEW',
  remote_ref VARCHAR(128) NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NULL,
  last_error VARCHAR(1024) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by VARCHAR(36) NULL,
  updated_by VARCHAR(36) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_inv_operation_no (operation_no),
  UNIQUE KEY uk_inv_operation_request (tenant_id, inventory_owner_ref, kind, request_key),
  KEY idx_inv_operation_retry (phase, next_retry_at),
  KEY idx_inv_operation_biz (tenant_id, biz_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存业务操作恢复日志；非库存余额';
CREATE TABLE inv_operation_step (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  operation_no VARCHAR(64) NOT NULL,
  step_code VARCHAR(32) NOT NULL,
  request_key VARCHAR(128) COLLATE utf8mb4_bin NULL,
  remote_ref VARCHAR(128) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / UNKNOWN / COMPLETED / FAILED',
  result_payload LONGTEXT NULL,
  last_error VARCHAR(1024) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by VARCHAR(36) NULL,
  updated_by VARCHAR(36) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_inv_operation_step (operation_no, step_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存操作分步结果';
