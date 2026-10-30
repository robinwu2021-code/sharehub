-- ============================================================
-- V197 库存流程卡点告警（A2 PB-C5 / A3 PB-E1；判定器 InventoryEvaluator）
--   INTAKE_OVERDUE         到仓待检单到达超过 N 天（inv.intake.overdue_days，默认 7）未处理完 → 仓管待办
--   REMOVAL_RETURN_MISSING 撤机完工去向回仓，超过 N 分钟（inv.removal.return_grace_minutes，默认 30）仍无回仓调拨单 → 运营待办
--   告警对象类型加 INTAKE（到仓待检单）
-- ============================================================
ALTER TABLE dev_alarm
  MODIFY COLUMN subject_type VARCHAR(16) NULL
    COMMENT 'SITE / CABINET / SLOT / ORDER / USER / POWERBANK / CONTRACT / PAYEE / AGENT / WORK_ORDER / PAYMENT / INTAKE';
ALTER TABLE dev_alarm_code
  MODIFY COLUMN subject_type VARCHAR(16) NOT NULL DEFAULT 'CABINET'
    COMMENT 'SITE 站点 / CABINET 机柜 / SLOT 仓位 / ORDER 订单 / USER 用户 / POWERBANK 充电宝 / CONTRACT 合同 / PAYEE 收款方 / AGENT 代理 / WORK_ORDER 工单 / PAYMENT 支付单 / INTAKE 到仓待检单';

INSERT INTO dev_alarm_code
  (code, message, message_en, level, suggestion, auto_work_order, domain, subject_type, eval_type, hold_minutes, window_minutes, threshold,
   business_hours_only, base_priority, impact_adjust, disposition, owner_role, wo_delay_minutes, merge_scope, recover_rule, recover_hold_minutes, supersedes, enabled, builtin)
VALUES
  ('INTAKE_OVERDUE','到仓待检超期','Intake overdue','WARN','仓管逐台质检并入库或处置；单据处理完告警自动消除',0,'ASSET','INTAKE','STATE',0,NULL,7,0,'MEDIUM',0,'TODO','WAREHOUSE',0,'DEVICE','SIGNAL_CLEAR',0,NULL,1,1),
  ('REMOVAL_RETURN_MISSING','撤机未生成回仓单','Removal without return transfer','WARN','检查是否已建仓与默认回仓仓（inv.return_warehouse），在出库池或调拨处补建回仓单；生成后自动消除',0,'ASSET','WORK_ORDER','STATE',0,NULL,NULL,0,'MEDIUM',0,'TODO','OPS',0,'DEVICE','SIGNAL_CLEAR',0,NULL,1,1)
ON DUPLICATE KEY UPDATE message = VALUES(message), suggestion = VALUES(suggestion), enabled = VALUES(enabled);

INSERT INTO dev_alarm_route (alarm_code, cause, disposition, wo_type, priority_delta, fallback) VALUES
  ('INTAKE_OVERDUE', '*', 'TODO', NULL, 0, NULL),
  ('REMOVAL_RETURN_MISSING', '*', 'TODO', NULL, 0, NULL)
ON DUPLICATE KEY UPDATE disposition = VALUES(disposition), wo_type = VALUES(wo_type), fallback = VALUES(fallback);

INSERT INTO dev_alarm_rule (rule_no, tenant_id, alarm_code, target, channel, method, quiet_start, quiet_end, escalate_minutes, status) VALUES
  ('AR-INV-INTAKE-OVERDUE', 'MAIN', 'INTAKE_OVERDUE', 'WAREHOUSE', 'PUSH', 'INSTANT', '20:00', '09:00', NULL, 'ACTIVE'),
  ('AR-INV-REMOVAL-RETURN', 'MAIN', 'REMOVAL_RETURN_MISSING', 'OPS', 'PUSH', 'INSTANT', '20:00', '09:00', NULL, 'ACTIVE')
ON DUPLICATE KEY UPDATE target = VALUES(target), channel = VALUES(channel);
