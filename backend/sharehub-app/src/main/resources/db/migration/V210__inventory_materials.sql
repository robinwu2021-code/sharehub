-- ============================================================
-- 库存管理 · 物料（用户 2026-10-29「库存管理下的所有功能都要实现」，物料账直接接 ai-shop 库存服务）
--
--   inv_material            物料档案（宿主主数据）：大类 / 跟踪方式 / 单位；同步到库存服务 items/by-ref/{物料号}。
--                            机柜、充电宝两条内置物料（device_type 指向设备流程，只读、不进物料单据）。
--   inv_material_doc(_line) 物料单据的宿主记录：账在库存服务（入库 / 出库 / 调拨 = 一出一入），这里存单头、明细与对方单号，
--                            供列表、追溯与「有流水后跟踪方式不可改」的判定。
--   inv_material_threshold  物料安全库存（仓 × 物料，0 = 不预警）。
-- 菜单：物料档案 / 物料单据 / 物料库存置就绪；「出库池」更名「出库单」（与入库单对应，用户 2026-10-29）。
-- ============================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS inv_material (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  material_no         VARCHAR(36)  NOT NULL,
  tenant_id           VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  name                VARCHAR(128) NOT NULL,
  category            VARCHAR(16)  NOT NULL COMMENT 'DEVICE 整机设备 / SPARE_PART 备件配件 / CONSUMABLE 耗材物料',
  tracking_mode       VARCHAR(16)  NOT NULL COMMENT 'SERIAL 逐件 / QUANTITY 按数量',
  uom                 VARCHAR(16)  NOT NULL COMMENT '计量单位',
  spec                VARCHAR(128)     NULL,
  vendor_code         VARCHAR(32)      NULL COMMENT '设备厂商（厂商接入）',
  default_supplier_no VARCHAR(36)      NULL COMMENT '默认供应商',
  device_type         VARCHAR(16)      NULL COMMENT '内置物料对应的设备类型：CABINET / POWERBANK；空 = 普通物料',
  archived_at         DATETIME(3)      NULL,
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by          VARCHAR(36)      NULL,
  updated_by          VARCHAR(36)      NULL,
  version             BIGINT       NOT NULL DEFAULT 0,
  deleted             TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_material_no (material_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物料档案';

INSERT INTO inv_material (material_no, tenant_id, name, category, tracking_mode, uom, spec, device_type)
SELECT * FROM (
  SELECT 'MT0001' AS material_no, 'MAIN' AS tenant_id, '共享充电宝机柜' AS name, 'DEVICE' AS category, 'SERIAL' AS tracking_mode,
         '台' AS uom, '按型号见设备台账' AS spec, 'CABINET' AS device_type
  UNION ALL SELECT 'MT0002', 'MAIN', '充电宝', 'DEVICE', 'SERIAL', '块', NULL, 'POWERBANK'
) AS v WHERE NOT EXISTS (SELECT 1 FROM (SELECT material_no FROM inv_material) t WHERE t.material_no = v.material_no);

CREATE TABLE IF NOT EXISTS inv_material_doc (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  doc_no            VARCHAR(36)  NOT NULL,
  tenant_id         VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  kind              VARCHAR(16)  NOT NULL COMMENT 'INBOUND 入库 / OUTBOUND 出库 / TRANSFER 调拨',
  purpose           VARCHAR(16)  NOT NULL COMMENT 'PURCHASE 采购 / OPENING 期初 / RETURN 退回 / REQUISITION 领用 / SCRAP 报废 / RETURN_SUPPLIER 退供应商 / TRANSFER 调拨',
  warehouse_no      VARCHAR(36)  NOT NULL,
  warehouse_name    VARCHAR(128)     NULL,
  to_warehouse_no   VARCHAR(36)      NULL COMMENT '调拨的调入仓',
  to_warehouse_name VARCHAR(128)     NULL,
  supplier_no       VARCHAR(36)      NULL,
  supplier_name     VARCHAR(128)     NULL,
  reason_code       VARCHAR(16)      NULL COMMENT 'SCRAP 必填：BROKEN 损坏 / OBSOLETE 淘汰 / OTHER 其他',
  receiver_no       VARCHAR(36)      NULL COMMENT '领用人工号',
  wo_no             VARCHAR(36)      NULL COMMENT '领用关联工单',
  currency          VARCHAR(8)       NULL,
  note              VARCHAR(512) NOT NULL,
  total_qty         INT          NOT NULL DEFAULT 0,
  remote_out_no     VARCHAR(64)      NULL COMMENT '库存服务出库单号',
  remote_in_no      VARCHAR(64)      NULL COMMENT '库存服务入库单号',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by        VARCHAR(36)      NULL,
  updated_by        VARCHAR(36)      NULL,
  version           BIGINT       NOT NULL DEFAULT 0,
  deleted           TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_material_doc_no (doc_no),
  KEY idx_material_doc_wh (warehouse_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物料单据（宿主记录，账在库存服务）';

CREATE TABLE IF NOT EXISTS inv_material_doc_line (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  doc_no        VARCHAR(36)   NOT NULL,
  line_no       INT           NOT NULL,
  material_no   VARCHAR(36)   NOT NULL,
  material_name VARCHAR(128)      NULL,
  qty           INT           NOT NULL,
  serial_nos    TEXT              NULL COMMENT '逐件物料的序列号，逗号分隔',
  unit_cost     DECIMAL(12,2)     NULL,
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_material_doc_line (doc_no, line_no),
  KEY idx_material_doc_line_mat (material_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物料单据明细';

CREATE TABLE IF NOT EXISTS inv_material_threshold (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  warehouse_no  VARCHAR(36)  NOT NULL,
  material_no   VARCHAR(36)  NOT NULL,
  min_qty       INT          NOT NULL DEFAULT 0 COMMENT '安全库存下限；0 = 不预警',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(36)      NULL,
  updated_by    VARCHAR(36)      NULL,
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_material_threshold (tenant_id, warehouse_no, material_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物料安全库存';

UPDATE iam_menu SET ready = 1
 WHERE parent_no = 'M_inventory' AND path IN ('/inventory?tab=materials', '/inventory?tab=material-docs', '/inventory?tab=material-stock');
UPDATE iam_menu SET name = '出库单', name_en = 'Outbound Orders', name_ar = 'أوامر الصرف'
 WHERE parent_no = 'M_inventory' AND path = '/inventory?tab=issued';
