-- 处置出库（库存缺口 P0 #2）：在库设备的报废 / 退供应商出库单。
-- 此前只有待检单上的不合格设备能处置；在库久了坏掉的、要整批退回供应商的，没有单据可走，只能逐台「报废」不留去向。
-- 一步过账：建单即出库（设备落终态、履历记 RETIRE），不设草稿 —— 与待检单处置同口径。
-- SCRAP 必须给原因码、不能带供应商；RETURN_SUPPLIER 必须给供应商（同 ai-shop 库存服务 OutboundServiceImpl.validate）。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS inv_disposal (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  disposal_no     VARCHAR(36)  NOT NULL,
  tenant_id       VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  kind            VARCHAR(16)  NOT NULL COMMENT 'SCRAP 报废 / RETURN_SUPPLIER 退供应商',
  warehouse_no    VARCHAR(36)  NOT NULL COMMENT '出库的仓',
  warehouse_name  VARCHAR(128)     NULL COMMENT '仓名快照',
  supplier_no     VARCHAR(36)      NULL COMMENT 'RETURN_SUPPLIER 必填',
  supplier_name   VARCHAR(128)     NULL COMMENT '供应商名称快照',
  reason_code     VARCHAR(16)      NULL COMMENT 'SCRAP 必填：BROKEN 损坏 / OBSOLETE 淘汰 / OTHER 其他',
  note            VARCHAR(512) NOT NULL COMMENT '说明',
  item_count      INT          NOT NULL DEFAULT 0,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by      VARCHAR(36)      NULL,
  updated_by      VARCHAR(36)      NULL,
  version         BIGINT       NOT NULL DEFAULT 0,
  deleted         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_disposal_no (disposal_no),
  KEY idx_disposal_wh (warehouse_no, created_at),
  KEY idx_disposal_supplier (supplier_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='处置出库单';

CREATE TABLE IF NOT EXISTS inv_disposal_item (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  disposal_no   VARCHAR(36)  NOT NULL,
  item_type     VARCHAR(16)  NOT NULL COMMENT 'CABINET / POWERBANK',
  item_no       VARCHAR(36)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_disposal_item (disposal_no, item_type, item_no),
  KEY idx_disposal_item_no (item_type, item_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='处置出库明细';

-- 菜单：仓储与出库 › 处置出库，排在出库池（sort 10）之后；原 11 起顺延
UPDATE iam_menu SET menu_no = CONCAT('M_tmp203_', id) WHERE parent_no = 'M_device';
UPDATE iam_menu SET sort = sort + 1 WHERE menu_no LIKE 'M_tmp203_%' AND sort >= 11;
UPDATE iam_menu SET menu_no = CONCAT('M_device__', sort) WHERE menu_no LIKE 'M_tmp203_%';

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
VALUES ('M_device__11', 'M_device', '处置出库', 'Disposals', 'الإخراج والإتلاف', 'ITEM', '/devices?tab=disposals', NULL, '仓储与出库', 11,
        'device:inventory:read', 1, 1, NULL, NULL, NULL, 0, NULL);
