SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 批次 C：站点与设备资产线（对齐清单 §七 C1–C9）
--
--   C1 现场勘测：loc_site_survey；首台设备上线门禁加「勘测通过」
--   C3 入库质检：dev_qc_record + 机柜 / 宝的 qc_status（NULL = 存量免检，PENDING/FAILED 不能调拨 / 上线）
--   C4 调拨逐件签收：签收时逐件核对，缺件 / 多件落 inv_asset_diff；机柜随调拨 IN_STOCK ⇄ IN_TRANSIT，签收回写所在仓
--   C5 装机工单：完工可带扫码点位；门禁加「装机工单完工」；完工后门禁全过自动上线
--   C6 装宝比例：门禁加「在柜宝占仓位 50%–80%」
--   C8 撤机：完工必填清点宝数，与系统在柜数不一致落资产差异；撤下的柜子生成回仓调拨单
--   C9 撤场关闭：按最后一份合同生成结算调整项（押金 / 进场费），财务确认后并入下一次场地方出账
-- ============================================================

-- —— C1 现场勘测 ——
CREATE TABLE IF NOT EXISTS loc_site_survey (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  survey_no       VARCHAR(36)  NOT NULL,
  tenant_id       VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  site_no         VARCHAR(36)  NOT NULL,
  signal_level    VARCHAR(16)  NOT NULL COMMENT 'STRONG 强 / GOOD 良 / WEAK 弱 / NONE 无信号',
  power_ok        TINYINT(1)   NOT NULL COMMENT '可接电源',
  placement_note  VARCHAR(512)     NULL COMMENT '可摆放位置',
  file_nos        VARCHAR(512)     NULL COMMENT '现场照片 fileNo，逗号分隔',
  result          VARCHAR(16)  NOT NULL COMMENT 'PASS 通过 / FAIL 不通过',
  note            VARCHAR(512)     NULL COMMENT '结论说明（不通过时必填）',
  surveyed_by     VARCHAR(36)  NOT NULL,
  surveyed_at     DATETIME(3)  NOT NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by      VARCHAR(36)      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_site_survey_no (survey_no),
  KEY idx_site_survey (site_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站点现场勘测（追加；以最近一次为准）';

-- —— C2 点位状态词表 ——
ALTER TABLE loc_location MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 可用 / PAUSED 停用';

-- —— C3 入库质检 ——
CREATE TABLE IF NOT EXISTS dev_qc_record (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  qc_no         VARCHAR(36)  NOT NULL,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  item_type     VARCHAR(16)  NOT NULL COMMENT 'CABINET 机柜 / POWERBANK 充电宝',
  item_no       VARCHAR(36)  NOT NULL,
  power_on      TINYINT(1)       NULL COMMENT '机柜：能开机联网',
  slots_ok      TINYINT(1)       NULL COMMENT '机柜：仓位锁与弹出正常',
  battery       INT              NULL COMMENT '充电宝：电量 %',
  cycles        INT              NULL COMMENT '充电宝：循环次数',
  result        VARCHAR(16)  NOT NULL COMMENT 'PASSED 通过 / FAILED 不通过',
  note          VARCHAR(512)     NULL,
  inspected_by  VARCHAR(36)  NOT NULL,
  inspected_at  DATETIME(3)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(36)      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_qc_no (qc_no),
  KEY idx_qc_item (item_type, item_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库质检记录（追加；以最近一次为准）';

ALTER TABLE dev_cabinet
  ADD COLUMN IF NOT EXISTS qc_status    VARCHAR(16) NULL COMMENT 'PENDING 待质检 / PASSED 通过 / FAILED 不通过；NULL = 质检上线前入库的存量，免检',
  ADD COLUMN IF NOT EXISTS warehouse_no VARCHAR(36) NULL COMMENT '在库时所在仓（调拨签收回写）';
ALTER TABLE dev_powerbank
  ADD COLUMN IF NOT EXISTS qc_status    VARCHAR(16) NULL COMMENT 'PENDING 待质检 / PASSED 通过 / FAILED 不通过；NULL = 存量，免检',
  ADD COLUMN IF NOT EXISTS warehouse_no VARCHAR(36) NULL COMMENT '在库时所在仓（调拨签收回写）';

-- —— C4 / C8 调拨签收与资产差异 ——
ALTER TABLE inv_transfer
  ADD COLUMN IF NOT EXISTS source_type  VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 人工建单 / REMOVAL 撤机回仓',
  ADD COLUMN IF NOT EXISTS source_ref   VARCHAR(36)     NULL COMMENT 'REMOVAL 时为撤机工单号',
  ADD COLUMN IF NOT EXISTS shipped_at   DATETIME(3)     NULL,
  ADD COLUMN IF NOT EXISTS received_at  DATETIME(3)     NULL,
  ADD COLUMN IF NOT EXISTS received_by  VARCHAR(36)     NULL,
  ADD COLUMN IF NOT EXISTS receive_note VARCHAR(512)    NULL,
  ADD KEY IF NOT EXISTS idx_transfer_source (source_type, source_ref);

CREATE TABLE IF NOT EXISTS inv_asset_diff (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  diff_no       VARCHAR(36)  NOT NULL,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  source_type   VARCHAR(16)  NOT NULL COMMENT 'TRANSFER 调拨签收 / REMOVAL 撤机清点',
  source_ref    VARCHAR(36)  NOT NULL COMMENT '调拨单号 / 撤机工单号',
  kind          VARCHAR(16)  NOT NULL COMMENT 'MISSING 缺件 / EXTRA 多件 / COUNT_MISMATCH 数量不符',
  item_type     VARCHAR(16)  NOT NULL COMMENT 'CABINET 机柜 / POWERBANK 充电宝',
  item_no       VARCHAR(36)      NULL COMMENT '逐件差异的件号；数量差异为空',
  site_no       VARCHAR(36)      NULL,
  cabinet_no    VARCHAR(36)      NULL,
  expected_qty  INT              NULL,
  actual_qty    INT              NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 待处理 / RESOLVED 已处理',
  resolve_note  VARCHAR(512)     NULL,
  resolved_by   VARCHAR(36)      NULL,
  resolved_at   DATETIME(3)      NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(36)      NULL,
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by    VARCHAR(36)      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_asset_diff_no (diff_no),
  KEY idx_asset_diff_source (source_type, source_ref),
  KEY idx_asset_diff_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产差异（调拨签收缺件多件、撤机清点数量不符）';

-- —— C5 / C8 工单完工附带的现场数据 ——
ALTER TABLE wo_handle
  ADD COLUMN IF NOT EXISTS location_no  VARCHAR(36) NULL COMMENT '装机：现场扫码的点位',
  ADD COLUMN IF NOT EXISTS counted_qty  INT         NULL COMMENT '撤机：现场清点的充电宝数';

-- —— C9 结算调整项 ——
CREATE TABLE IF NOT EXISTS stl_adjustment (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  adj_no        VARCHAR(36)   NOT NULL,
  tenant_id     VARCHAR(36)   NOT NULL DEFAULT 'MAIN',
  payee_type    VARCHAR(16)   NOT NULL COMMENT 'VENUE 场地方 / AGENT 代理',
  payee_no      VARCHAR(36)   NOT NULL,
  payee_name    VARCHAR(128)      NULL,
  kind          VARCHAR(24)   NOT NULL COMMENT 'DEPOSIT_REFUND 押金退还 / ENTRY_FEE_SETTLE 进场费结清',
  site_no       VARCHAR(36)       NULL,
  contract_no   VARCHAR(36)       NULL,
  amount        DECIMAL(18,2) NOT NULL COMMENT '对收款方应付的调整：正 = 平台多付给对方，负 = 对方应返还平台',
  suggested_amount DECIMAL(18,2)  NULL COMMENT '系统按合同算出的建议值（确认时可改，留痕）',
  currency      VARCHAR(8)    NOT NULL DEFAULT 'AED',
  status        VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待财务确认 / CONFIRMED 已确认待出账 / SETTLED 已并入结算单 / VOID 作废',
  settle_no     VARCHAR(36)       NULL COMMENT '并入的结算单',
  source        VARCHAR(24)   NOT NULL COMMENT 'SITE_CLOSED 撤场关闭',
  note          VARCHAR(512)      NULL,
  confirmed_by  VARCHAR(36)       NULL,
  confirmed_at  DATETIME(3)       NULL,
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(36)       NULL,
  updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by    VARCHAR(36)       NULL,
  version       BIGINT        NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stl_adjustment_no (adj_no),
  UNIQUE KEY uk_stl_adjustment_src (source, site_no, contract_no, kind),
  KEY idx_stl_adjustment_payee (payee_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算调整项（撤场结清押金 / 进场费等，不走提现）';

ALTER TABLE stl_settlement_detail
  MODIFY COLUMN ref_type VARCHAR(16) NOT NULL COMMENT 'ORDER 订单 / SHARE 分润记录 / ADJUST 结算调整项';

-- —— 系统参数 ——
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name) VALUES
  ('MAIN', 'device.load.min_ratio',   '上线门禁：在柜宝占仓位下限', '0.5', '设备'),
  ('MAIN', 'device.load.max_ratio',   '上线门禁：在柜宝占仓位上限（满装没地方还）', '0.8', '设备'),
  ('MAIN', 'device.qc.min_battery',   '充电宝质检：最低电量 %', '60', '设备'),
  ('MAIN', 'device.qc.max_cycles',    '充电宝质检：最大循环次数', '500', '设备'),
  ('MAIN', 'site.withdraw.lead_days', '撤场计划日至少提前天数', '7', '站点'),
  ('MAIN', 'inv.return_warehouse',    '撤机回仓默认仓库（空 = 取第一个仓库）', '', '库存')
ON DUPLICATE KEY UPDATE label = VALUES(label), group_name = VALUES(group_name);

-- —— 事件词表：站点撤场关闭（C9 结算调整项订阅）——
ALTER TABLE sys_outbox
  MODIFY COLUMN event_type VARCHAR(64) NOT NULL
  COMMENT 'ASSET_ASSIGNED/CONTRACT_SIGNED/ORDER_SETTLED/CABINET_WENT_LIVE/DEVICE_SIGNAL/WORK_ORDER_COMPLETED/SITE_CLOSED';
