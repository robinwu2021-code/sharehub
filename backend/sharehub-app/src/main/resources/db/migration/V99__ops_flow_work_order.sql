SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 工单承接业务告警（TDD-运营核心流程/06）
--
-- · priority / wo_dispatch.action 此前无词表注释（存量值 LOW/MEDIUM/HIGH/URGENT、DISPATCH/ACCEPT/REJECT）；
-- · 撤单（告警自动恢复且未接单）与并单（柜级告警被站点级取代）都落 CLOSED，原因分别 WITHDRAWN / DUPLICATE；
-- · 完工复核：review_status 只对告警来源的工单写；
-- · SLA 从「按类型」改为「类型 × 优先级」，priority='*' 为该类型默认（不用 NULL：NULL 不参与唯一键）。
-- ============================================================
ALTER TABLE wo_order
  MODIFY COLUMN priority VARCHAR(8) NOT NULL DEFAULT 'MEDIUM' COMMENT 'LOW 低 / MEDIUM 中 / HIGH 高 / URGENT 紧急',
  ADD COLUMN IF NOT EXISTS assignee_type      VARCHAR(16)   NULL COMMENT 'EMPLOYEE 平台员工 / AGENT 代理商',
  ADD COLUMN IF NOT EXISTS dispatch_strategy  VARCHAR(16)   NULL COMMENT 'OWNER 按运维责任人 / MANUAL 人工 / NEAREST 就近 / LOAD 负载 / GRAB 抢单',
  ADD COLUMN IF NOT EXISTS fault_reason_code  VARCHAR(32)   NULL COMMENT 'NETWORK 网络 / POWER 供电 / SLOT_MECH 仓位机械 / LOCK 锁 / BATTERY 电池 / SCREEN 屏 / DAMAGE 人为损坏 / OTHER 其他',
  ADD COLUMN IF NOT EXISTS review_status      VARCHAR(16)   NULL COMMENT 'PASSED 复核通过 / FAILED 复核未通过（仅告警来源的工单）',
  ADD COLUMN IF NOT EXISTS review_detail      VARCHAR(2000) NULL COMMENT '复核明细 JSON',
  ADD COLUMN IF NOT EXISTS reviewed_at        DATETIME(3)   NULL,
  ADD COLUMN IF NOT EXISTS merged_into_wo_no  VARCHAR(36)   NULL COMMENT '并单去向（close_reason=DUPLICATE）',
  ADD COLUMN IF NOT EXISTS alarm_recovered_at DATETIME(3)   NULL COMMENT '关联告警全部恢复的时刻（已接单的单不撤，只标记）';

ALTER TABLE wo_sla_rule
  ADD COLUMN IF NOT EXISTS priority VARCHAR(8) NOT NULL DEFAULT '*' COMMENT '* 类型默认 / LOW / MEDIUM / HIGH / URGENT',
  DROP INDEX IF EXISTS uk_sla_rule_type,
  ADD UNIQUE KEY IF NOT EXISTS uk_sla_rule_type_prio (tenant_id, wo_type, priority);

ALTER TABLE wo_dispatch
  MODIFY COLUMN strategy VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'OWNER 按运维责任人 / MANUAL 人工 / NEAREST 就近 / LOAD 负载 / GRAB 抢单',
  MODIFY COLUMN action   VARCHAR(24) NULL COMMENT 'DISPATCH 派单 / ACCEPT 接单 / REJECT 退回 / WITHDRAW 撤单 / MERGE 并单 / PRIORITY_UP 升优先级 / REVIEW 复核 / NOTE 备注';

ALTER TABLE wo_handle
  ADD COLUMN IF NOT EXISTS fault_reason_code VARCHAR(32)  NULL COMMENT '同 wo_order.fault_reason_code',
  ADD COLUMN IF NOT EXISTS file_nos          VARCHAR(512) NULL COMMENT '现场照片 fileNo，逗号分隔';

-- SLA 默认（类型 × 优先级；分钟）。已有同类型的「默认行」不覆盖（运营可能改过）。
INSERT INTO wo_sla_rule (sla_no, tenant_id, wo_type, priority, response_mins, resolve_mins, active) VALUES
  ('SLA-FAULT-URGENT', 'MAIN', 'FAULT', 'URGENT', 30, 240, 1),
  ('SLA-FAULT-HIGH',   'MAIN', 'FAULT', 'HIGH',  120, 1440, 1),
  ('SLA-FAULT-MEDIUM', 'MAIN', 'FAULT', 'MEDIUM', 480, 2880, 1)
ON DUPLICATE KEY UPDATE sla_no = sla_no;
