SET NAMES utf8mb4;
-- ============================================================
-- 充电宝「疑似丢失」：失联满 N 天只打标记 + 人工确认，不自动转 LOST（2026-09-25 定）
--
-- 【为什么不自动转丢失】LOST 带资产与钱的后果（核销、可能向最后借用人追偿、代理资产调整）。
--   失联多数是上报缺失（柜子离线、归还未识别），系统自动判丢失一旦误判，纠错成本与客诉都高。
--   所以：失联 24 小时 → 已有的「宝失联」告警 + 核查待办；满 N 天（默认 7）→ 标疑似丢失；
--   运维核实后二选一：「确认丢失」（RENTED → LOST，走状态机 CONFIRM_LOST）或「已找回」（清标记、重置失联计时）；
--   疑似满 M 天（默认 30）仍无人处理 → 升级通知运维主管（只升级一次）。
--
-- 【是标记不是状态】不在 PowerbankStatus 里加 SUSPECTED_LOST：宝仍在借出中（RENTED），
--   订单、分润、告警都按 RENTED 算；加状态会让这些口径全部要改，而它只是「待人核实」这一层信息。
-- ============================================================

ALTER TABLE dev_powerbank
  ADD COLUMN IF NOT EXISTS suspected_lost_at DATETIME(3) NULL COMMENT '疑似丢失标记时间（失联满 N 天；确认丢失 / 找回后清空）',
  ADD COLUMN IF NOT EXISTS lost_escalated_at DATETIME(3) NULL COMMENT '疑似丢失久未处理、已升级通知的时间（每轮疑似只升级一次）';
ALTER TABLE dev_powerbank ADD INDEX IF NOT EXISTS idx_pb_suspected_lost (tenant_id, suspected_lost_at);

INSERT INTO sys_param (tenant_id, param_key, label, value, group_name) VALUES
  ('MAIN', 'asset.powerbank.suspect_lost_days', '充电宝失联满 N 天标记疑似丢失', '7', '资产'),
  ('MAIN', 'asset.powerbank.lost_escalate_days', '疑似丢失满 N 天仍未处理，升级通知运维主管', '30', '资产')
ON DUPLICATE KEY UPDATE label = VALUES(label), group_name = VALUES(group_name);
