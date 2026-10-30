-- 到仓待检（docs/technical/独立进销存/D §1.3 PB-C1 ~ C4 · S2.2）：新到货先登记待检，业务质检通过才入库。
-- 待检期间设备已实物在仓、但还不在库存账上 —— 这张单是它唯一的记录（D §6.3「到仓待检」盲区）。
-- 本期只做到货（ARRIVAL）；撤机回仓（RETURN）生成待检单随下一步改造撤机流程。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS inv_intake (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  intake_no       VARCHAR(36)  NOT NULL,
  tenant_id       VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  kind            VARCHAR(16)  NOT NULL COMMENT 'ARRIVAL 到货 / RETURN 撤机回仓',
  warehouse_no    VARCHAR(36)  NOT NULL COMMENT '到达的仓',
  warehouse_name  VARCHAR(128)     NULL COMMENT '登记时的仓名快照',
  source_ref      VARCHAR(64)      NULL COMMENT '到货单号 / 撤机工单号',
  arrived_at      DATETIME(3)  NOT NULL,
  status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 处理中 / DONE 已完成（明细全部入库或处置）',
  note            VARCHAR(512)     NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by      VARCHAR(36)      NULL,
  updated_by      VARCHAR(36)      NULL,
  version         BIGINT       NOT NULL DEFAULT 0,
  deleted         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_intake_no (intake_no),
  KEY idx_intake_status (status, arrived_at),
  KEY idx_intake_wh (warehouse_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='到仓待检单';

CREATE TABLE IF NOT EXISTS inv_intake_item (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  intake_no         VARCHAR(36)  NOT NULL,
  item_type         VARCHAR(16)  NOT NULL COMMENT 'CABINET / POWERBANK',
  item_no           VARCHAR(36)  NOT NULL,
  qc_no             VARCHAR(36)      NULL COMMENT '最近一次质检记录号 = 入库放行凭证',
  qc_result         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PASSED / FAILED',
  disposition       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待处理 / INBOUND 已入库 / RETURN_SUPPLIER 退供应商 / SCRAP 报废',
  disposition_note  VARCHAR(512)     NULL,
  handled_by        VARCHAR(36)      NULL,
  handled_at        DATETIME(3)      NULL,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_intake_item (intake_no, item_type, item_no),
  KEY idx_intake_item_no (item_type, item_no, disposition)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='到仓待检明细';

INSERT IGNORE INTO iam_permission (code, name, module) VALUES
  ('device:inventory:inbound', '到货登记 / 入库 / 不合格处置', 'device');

INSERT IGNORE INTO iam_role_perm (role_no, perm_code) VALUES
  ('WAREHOUSE', 'device:inventory:inbound');
