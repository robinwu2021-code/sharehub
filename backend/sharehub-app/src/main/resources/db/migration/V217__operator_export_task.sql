-- 执行计划S01：兼容扩展，不回填猜测的历史业务事实。
SET NAMES utf8mb4;

CREATE TABLE sys_export_task (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_no VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  kind VARCHAR(64) NOT NULL,
  filter_snapshot LONGTEXT NOT NULL,
  scope_snapshot LONGTEXT NOT NULL,
  format VARCHAR(8) NOT NULL COMMENT 'XLSX / PDF',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / COMPLETED / FAILED / EXPIRED',
  progress INT NOT NULL DEFAULT 0,
  file_no VARCHAR(36) NULL,
  expires_at DATETIME(3) NULL,
  error VARCHAR(1024) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by VARCHAR(36) NULL,
  updated_by VARCHAR(36) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_export_task_no (task_no),
  UNIQUE KEY uk_export_request (tenant_id, created_by, kind, request_key),
  KEY idx_export_actor (tenant_id, created_by, created_at),
  KEY idx_export_status (status, created_at),
  CONSTRAINT chk_export_progress CHECK (progress BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='受控异步导出任务；下载时重新检查权限';
