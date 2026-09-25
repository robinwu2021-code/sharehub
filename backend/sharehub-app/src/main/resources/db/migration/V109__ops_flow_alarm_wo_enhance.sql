SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 批次 E：告警与工单增强（对齐清单 §七 E1–E4）
--
--   E1 窗口计数判定：设备信号落 dev_signal_log（追加）；心跳间隔 > 3 分钟后恢复时合成一条 LINK_RESTORED（= 一次掉线）。
--      启用 EJECT_FAIL_RATE（同柜 60 分钟弹出失败 ≥ 3 次，柜子停借 + 维修单）、新增 FREQUENT_OFFLINE（24 小时掉线 > 5 次）、
--      启用 POWERBANK_MISSING（宝不在柜、无进行中订单、无在途调拨，超过 24 小时）
--   E2 工单超 SLA 升级通知：响应超时 → 规则 escalate_to（默认运维）；解决超时 → resolve_escalate_to（默认管理员）；各通知一次
--   E3 抢单：未派单工单进抢单池，接单即派给自己（dispatch_strategy=GRAB，词表已有）
--   E4 短信 / 邮件：告警通知规则的 SMS / EMAIL 通道按员工联系方式发送（notify_log 目标脱敏）
-- ============================================================

CREATE TABLE IF NOT EXISTS dev_signal_log (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  cabinet_no   VARCHAR(36)  NOT NULL,
  code         VARCHAR(32)  NOT NULL COMMENT '同 dev_event_code.code（LINK_RESTORED 为系统据心跳间隔合成）',
  slot_index   INT              NULL,
  powerbank_no VARCHAR(36)      NULL,
  occurred_at  DATETIME(3)  NOT NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by   VARCHAR(36)      NULL,
  PRIMARY KEY (id),
  KEY idx_signal_log (cabinet_no, code, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备信号流水（追加；窗口计数型告警的输入，心跳不入此表）';

INSERT INTO dev_event_code (code, name, name_en, category, scope, protective_action, clears_code, feeds) VALUES
  ('LINK_RESTORED', '掉线后恢复', 'Link restored', 'LINK', 'CABINET', 'NONE', NULL, '频繁掉线计数（系统据心跳间隔合成）')
ON DUPLICATE KEY UPDATE name = VALUES(name), name_en = VALUES(name_en), feeds = VALUES(feeds);

ALTER TABLE dev_alarm
  MODIFY COLUMN cause VARCHAR(16) NULL
    COMMENT 'OFFLINE 离线 / UNSTABLE 链路不稳 / NO_STOCK 无宝 / FULL 满柜 / FAULT 故障停用 / MIXED 混合 / NO_CONTRACT 无合同 / EXPIRING 合同将到期 / LOW_BATTERY 低电堆积 / AGED 宝老化 / MISSING 宝失联 / CANCEL_FAILED 撤单失败 / SN_SEEN 已识别未结单 / HAZARD 安全隐患 / OVERHEAT 过热';

UPDATE dev_alarm_code SET enabled = 1, window_minutes = 60, threshold = 3 WHERE code = 'EJECT_FAIL_RATE';
UPDATE dev_alarm_code SET enabled = 1, hold_minutes = 0,
       suggestion = '仓管核查最后位置（最后所在柜、最后订单、调拨单）；找回后告警自动消除'
 WHERE code = 'POWERBANK_MISSING';

INSERT INTO dev_alarm_code
  (code, message, message_en, level, suggestion, auto_work_order, domain, subject_type, eval_type, hold_minutes, window_minutes, threshold,
   business_hours_only, base_priority, impact_adjust, disposition, owner_role, wo_delay_minutes, merge_scope, recover_rule, recover_hold_minutes, supersedes, enabled, builtin)
VALUES
  ('FREQUENT_OFFLINE','频繁掉线','Frequent disconnection','WARN','查现场网络与供电（信号弱、插座松动、共用电源被关）',1,'AVAILABILITY','CABINET','COUNT',0,1440,6,0,'MEDIUM',1,'WORK_ORDER',NULL,0,'DEVICE','SIGNAL_CLEAR',60,NULL,1,1)
ON DUPLICATE KEY UPDATE message = VALUES(message), message_en = VALUES(message_en), level = VALUES(level), suggestion = VALUES(suggestion),
  domain = VALUES(domain), subject_type = VALUES(subject_type), eval_type = VALUES(eval_type), builtin = VALUES(builtin);

INSERT INTO dev_alarm_route (alarm_code, cause, disposition, wo_type, priority_delta, fallback) VALUES
  ('EJECT_FAIL_RATE','*','WORK_ORDER','FAULT',0,NULL),
  ('FREQUENT_OFFLINE','*','WORK_ORDER','FAULT',0,NULL),
  ('POWERBANK_MISSING','*','TODO',NULL,0,NULL)
ON DUPLICATE KEY UPDATE disposition = VALUES(disposition), wo_type = VALUES(wo_type), fallback = VALUES(fallback);

-- —— E2 SLA 升级 ——
ALTER TABLE wo_sla_rule
  MODIFY COLUMN escalate_to VARCHAR(64) NULL COMMENT '响应超时升级到（角色码 / 员工号，逗号分隔）',
  ADD COLUMN IF NOT EXISTS resolve_escalate_to VARCHAR(64) NULL COMMENT '解决超时升级到（角色码 / 员工号，逗号分隔）';
ALTER TABLE wo_sla
  ADD COLUMN IF NOT EXISTS respond_escalated_at DATETIME(3) NULL COMMENT '响应超时升级通知已发（幂等）',
  ADD COLUMN IF NOT EXISTS resolve_escalated_at DATETIME(3) NULL COMMENT '解决超时升级通知已发（幂等）';

INSERT INTO sys_param (tenant_id, param_key, label, value, group_name) VALUES
  ('MAIN', 'wo.escalate.respond_to', '工单响应超时默认升级到（规则未配置时；角色码 / 员工号）', 'OPS', '工单'),
  ('MAIN', 'wo.escalate.resolve_to', '工单解决超时默认升级到（规则未配置时；角色码 / 员工号）', 'ADMIN', '工单'),
  ('MAIN', 'asset.powerbank.missing_hours', '充电宝失联：不在柜且无订单 / 调拨超过 N 小时', '24', '设备')
ON DUPLICATE KEY UPDATE label = VALUES(label), group_name = VALUES(group_name);
