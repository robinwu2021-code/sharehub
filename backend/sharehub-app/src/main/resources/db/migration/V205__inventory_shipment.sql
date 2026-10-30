-- 发货物流信息（库存缺口 P1 #4）：设备实物离开时记承运商 / 运单号，可按单据或设备号查。
--   doc_type：TRANSFER 调拨发出 / ISSUE 出库给商家 / DISPOSAL 退供应商；doc_ref = 调拨单号 / 商家号 / 处置单号。
--   不填承运商和运单号就不建（自提、当面交付）。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS inv_shipment (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  shipment_no   VARCHAR(36)  NOT NULL,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  doc_type      VARCHAR(16)  NOT NULL COMMENT 'TRANSFER 调拨发出 / ISSUE 出库给商家 / DISPOSAL 退供应商',
  doc_ref       VARCHAR(36)  NOT NULL COMMENT '调拨单号 / 商家号 / 处置单号',
  carrier       VARCHAR(64)      NULL COMMENT '承运商',
  tracking_no   VARCHAR(64)      NULL COMMENT '运单号',
  shipped_at    DATETIME(3)  NOT NULL,
  note          VARCHAR(512)     NULL,
  item_count    INT          NOT NULL DEFAULT 0,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(36)      NULL,
  updated_by    VARCHAR(36)      NULL,
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_shipment_no (shipment_no),
  KEY idx_shipment_doc (doc_type, doc_ref),
  KEY idx_shipment_tracking (tracking_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发货物流';

CREATE TABLE IF NOT EXISTS inv_shipment_item (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  shipment_no   VARCHAR(36)  NOT NULL,
  item_type     VARCHAR(16)  NOT NULL COMMENT 'CABINET / POWERBANK',
  item_no       VARCHAR(36)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_shipment_item (shipment_no, item_type, item_no),
  KEY idx_shipment_item_no (item_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发货物流明细';
