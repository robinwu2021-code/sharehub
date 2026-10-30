-- 执行计划S01：兼容扩展，不回填猜测的历史业务事实。
SET NAMES utf8mb4;

CREATE TABLE dev_delivery (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  delivery_no VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  agent_no VARCHAR(36) NOT NULL,
  source_warehouse_no VARCHAR(36) NULL,
  outbound_ref VARCHAR(128) NULL,
  operation_no VARCHAR(64) NULL,
  receiver_ref VARCHAR(36) NULL,
  handover_mode VARCHAR(24) NULL COMMENT 'IN_PERSON / CARRIER；历史未知可空',
  status VARCHAR(24) NOT NULL DEFAULT 'ISSUED' COMMENT 'ISSUED / CLAIMED / ACCEPTED / RETURNED / CANCELLED',
  claimed_at DATETIME(3) NULL,
  claimed_by VARCHAR(36) NULL,
  install_wo_no VARCHAR(36) NULL,
  merchant_confirmed_at DATETIME(3) NULL,
  merchant_confirmed_by VARCHAR(36) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by VARCHAR(36) NULL,
  updated_by VARCHAR(36) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_dev_delivery_no (delivery_no),
  KEY idx_dev_delivery_agent (tenant_id, agent_no, status, created_at),
  KEY idx_dev_delivery_operation (operation_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家设备交付凭证；不另建库存余额';
CREATE TABLE dev_delivery_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  delivery_no VARCHAR(36) NOT NULL,
  device_no VARCHAR(36) NOT NULL,
  item_type VARCHAR(24) NOT NULL DEFAULT 'CABINET' COMMENT 'CABINET / POWERBANK',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_delivery_device (delivery_no, device_no),
  KEY idx_delivery_device_history (device_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备交付明细；允许同设备未来再次交付';
ALTER TABLE inv_shipment ADD COLUMN delivery_no VARCHAR(36) NULL, ADD KEY idx_shipment_delivery (delivery_no);
ALTER TABLE dev_cabinet ADD COLUMN active_delivery_no VARCHAR(36) NULL COMMENT '当前交付引用；历史未知不回填';
