-- 安全库存与低库存预警（库存缺口 P1 #3）。
--   inv_stock_threshold：仓 × 设备类型的下限；可用量 = 在该仓在库、未绑点位、不在出库池（宝：不在柜内）、未归档。
--   min_qty = 0 即关闭（契约禁止 delete）。
--   告警 LOW_STOCK（判定器 InventoryEvaluator）：可用量低于下限 → 仓管待办；补货到下限即自动恢复关闭。
--   告警对象类型加 WAREHOUSE（subject_no = 仓号:设备类型）。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS inv_stock_threshold (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  warehouse_no  VARCHAR(36)  NOT NULL,
  item_type     VARCHAR(16)  NOT NULL COMMENT 'CABINET / POWERBANK',
  min_qty       INT          NOT NULL DEFAULT 0 COMMENT '安全库存下限；0 = 不预警',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(36)      NULL,
  updated_by    VARCHAR(36)      NULL,
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_threshold (tenant_id, warehouse_no, item_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='安全库存';

ALTER TABLE dev_alarm
  MODIFY COLUMN subject_type VARCHAR(16) NULL
    COMMENT 'SITE / CABINET / SLOT / ORDER / USER / POWERBANK / CONTRACT / PAYEE / AGENT / WORK_ORDER / PAYMENT / INTAKE / WAREHOUSE';
ALTER TABLE dev_alarm_code
  MODIFY COLUMN subject_type VARCHAR(16) NOT NULL DEFAULT 'CABINET'
    COMMENT 'SITE 站点 / CABINET 机柜 / SLOT 仓位 / ORDER 订单 / USER 用户 / POWERBANK 充电宝 / CONTRACT 合同 / PAYEE 收款方 / AGENT 代理 / WORK_ORDER 工单 / PAYMENT 支付单 / INTAKE 到仓待检单 / WAREHOUSE 仓库';

INSERT INTO dev_alarm_code
  (code, message, message_en, level, suggestion, auto_work_order, domain, subject_type, eval_type, hold_minutes, window_minutes, threshold,
   business_hours_only, base_priority, impact_adjust, disposition, owner_role, wo_delay_minutes, merge_scope, recover_rule, recover_hold_minutes, supersedes, enabled, builtin)
VALUES
  ('LOW_STOCK','低于安全库存','Below safety stock','WARN','安排采购到货或从其他仓调拨补货；可用量回到下限即自动消除。下限在「仓库 › 安全库存」设置',0,'ASSET','WAREHOUSE','STATE',0,NULL,NULL,0,'MEDIUM',0,'TODO','WAREHOUSE',0,'DEVICE','SIGNAL_CLEAR',0,NULL,1,1)
ON DUPLICATE KEY UPDATE message = VALUES(message), suggestion = VALUES(suggestion), enabled = VALUES(enabled);

INSERT INTO dev_alarm_route (alarm_code, cause, disposition, wo_type, priority_delta, fallback) VALUES
  ('LOW_STOCK', '*', 'TODO', NULL, 0, NULL)
ON DUPLICATE KEY UPDATE disposition = VALUES(disposition), wo_type = VALUES(wo_type), fallback = VALUES(fallback);

INSERT INTO dev_alarm_rule (rule_no, tenant_id, alarm_code, target, channel, method, quiet_start, quiet_end, escalate_minutes, status) VALUES
  ('AR-INV-LOW-STOCK', 'MAIN', 'LOW_STOCK', 'WAREHOUSE', 'PUSH', 'INSTANT', '20:00', '09:00', NULL, 'ACTIVE')
ON DUPLICATE KEY UPDATE target = VALUES(target), channel = VALUES(channel);
