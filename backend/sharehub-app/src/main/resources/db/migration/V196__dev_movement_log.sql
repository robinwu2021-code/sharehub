-- ============================================================
-- V196 设备履历（仓外，docs/technical/独立进销存/D §1.6 PB-F1，S2.1）
--   只追加：每一次位置或归属变化恰好一行（I8）。写入只经 MovementRecorder（ArchitectureTest 守），
--   与业务写同事务 —— 业务回滚则履历一起回滚，不会出现「履历说动了、设备没动」。
--   from_ref / to_ref 带前缀：WH: 仓 · AGENT: 商家出库池 · SITE: 站点 · LOC: 点位 · TRANSIT: 在途 · OUT: 离开（报废 / 遗失）
-- ============================================================
CREATE TABLE IF NOT EXISTS dev_movement_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  item_type     VARCHAR(16)  NOT NULL COMMENT 'CABINET 机柜 / POWERBANK 充电宝',
  item_no       VARCHAR(36)  NOT NULL,
  event         VARCHAR(24)  NOT NULL COMMENT 'ISSUE 出库 / CLAIM 签收 / REDIRECT 出库调整 / POOL_RETURN 出库池退回 / BIND 绑定 / UNBIND 解绑 / SHIP 调拨发货 / RECEIVE 调拨签收 / UNDEPLOY 撤机 / RETURN_INTAKE 回仓待检 / INBOUND 入库 / REASSIGN 换商家 / RETIRE 报废 / LOST 遗失',
  from_ref      VARCHAR(64)      NULL COMMENT '变化前位置（带前缀）',
  to_ref        VARCHAR(64)      NULL COMMENT '变化后位置（带前缀）',
  agent_before  VARCHAR(36)      NULL COMMENT '变化前归属商家',
  agent_after   VARCHAR(36)      NULL COMMENT '变化后归属商家',
  reason        VARCHAR(512)     NULL,
  source_ref    VARCHAR(64)      NULL COMMENT '来源单号（工单 / 调拨 / 待检单）',
  operator_no   VARCHAR(36)  NOT NULL,
  occurred_at   DATETIME(3)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_movement_item (item_type, item_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备履历（只追加）';
