-- ============================================================
-- V201 仓库管理与供应商（用户 2026-10-29「缺少仓库管理、供应商管理」；矩阵 OM03-05 / OM03-20）
--   inv_warehouse 加 archived_at：仓库只归档不删（契约禁止 delete；有在仓设备 / 未结待检单的不能归档）
--   inv_supplier：设备供应商（采购来源、退供应商的去向）
--   inv_intake 加 supplier_no：到货登记选供应商
--   权限码 device:inventory:warehouse：仓库与供应商的增改归档（库存主数据）
--   设备管理 › 入库与质检 加「供应商」叶子（到仓待检之后）；设备叶子整体按 sort 重编号
-- ============================================================
SET NAMES utf8mb4;

ALTER TABLE inv_warehouse ADD COLUMN archived_at DATETIME(3) NULL COMMENT '归档时间；空为启用' AFTER address;

CREATE TABLE IF NOT EXISTS inv_supplier (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  supplier_no   VARCHAR(36)  NOT NULL COMMENT '业务键',
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  name          VARCHAR(128) NOT NULL COMMENT '供应商名称',
  contact_name  VARCHAR(64)      NULL,
  phone         VARCHAR(32)      NULL,
  email         VARCHAR(128)     NULL,
  address       VARCHAR(256)     NULL,
  note          VARCHAR(512)     NULL,
  archived_at   DATETIME(3)      NULL COMMENT '归档时间；空为启用',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  created_by    VARCHAR(36)      NULL,
  updated_by    VARCHAR(36)      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_no (supplier_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备供应商';

ALTER TABLE inv_intake ADD COLUMN supplier_no VARCHAR(36) NULL COMMENT '供应商（到货登记时选）' AFTER source_ref;
ALTER TABLE inv_intake ADD COLUMN supplier_name VARCHAR(128) NULL COMMENT '供应商名称快照' AFTER supplier_no;

INSERT IGNORE INTO iam_permission (code, name, module) VALUES
  ('device:inventory:warehouse', '仓库与供应商 维护', 'device');

UPDATE iam_menu SET menu_no = CONCAT('M_tmp201_', id) WHERE parent_no = 'M_device';
UPDATE iam_menu SET sort = sort + 1 WHERE menu_no LIKE 'M_tmp201_%' AND sort >= 4;
UPDATE iam_menu SET menu_no = CONCAT('M_device__', sort) WHERE menu_no LIKE 'M_tmp201_%';

INSERT INTO iam_menu (menu_no, parent_no, name, name_en, name_ar, type, path, icon, group_name, sort, perm, phase, ready, module, modules, match_paths, pin_bottom, portal_for)
VALUES ('M_device__4', 'M_device', '供应商', 'Suppliers', 'الموردون', 'ITEM', '/devices?tab=suppliers', NULL, '入库与质检', 4,
        'device:inventory:read', 1, 1, NULL, NULL, NULL, 0, NULL);
