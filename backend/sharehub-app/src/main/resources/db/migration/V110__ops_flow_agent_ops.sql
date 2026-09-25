SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 批次 F：代理商（对齐清单 §七 F1–F5）
--
--   F1 代理停用冻结提现：服务层拒绝（申请 / 审批 / 打款），不新增状态 —— 已提交的单停在原状态即「挂起」
--   F2 代理停用改派名下工单：状态变更事件 → 工单侧改派平台员工（wo_dispatch.action 增 REASSIGN）
--   F3 代理清退：agt_exit 清退单，三步顺序门禁（收回资产 → 结清 → 关闭账号）
--   F4 平台接管超时代理工单：wo_order.taken_over_from / taken_over_at，wo_dispatch.action 增 TAKEOVER
--   F5 代理运维考核：agt_ops_assessment 月度结果；运维分成系数作用于下一个月的 OPERATE 分润；
--      启用 AGENT_SLA_BELOW（达成率低于阈值 → BD 待办）
-- ============================================================

ALTER TABLE wo_dispatch
  MODIFY COLUMN action VARCHAR(24) NULL
    COMMENT 'DISPATCH 派单 / ACCEPT 接单 / REJECT 退回 / WITHDRAW 撤单 / MERGE 并单 / PRIORITY_UP 升优先级 / REVIEW 复核 / NOTE 备注 / REASSIGN 代理停用改派 / TAKEOVER 平台接管';

ALTER TABLE wo_order
  ADD COLUMN IF NOT EXISTS taken_over_from VARCHAR(36) NULL COMMENT '被平台接管前的代理（F4；考核计被接管数）',
  ADD COLUMN IF NOT EXISTS taken_over_at   DATETIME(3) NULL;

CREATE TABLE IF NOT EXISTS agt_exit (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  exit_no       VARCHAR(36)  NOT NULL,
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  agent_no      VARCHAR(36)  NOT NULL,
  status        VARCHAR(24)  NOT NULL COMMENT 'RECLAIMING 收回资产中 / SETTLING 结清中 / CLOSING 待关闭账号 / CLOSED 已清退',
  reason        VARCHAR(512) NOT NULL,
  started_by    VARCHAR(36)  NOT NULL,
  started_at    DATETIME(3)  NOT NULL,
  reclaimed_at  DATETIME(3)      NULL,
  settled_at    DATETIME(3)      NULL,
  closed_at     DATETIME(3)      NULL,
  closed_by     VARCHAR(36)      NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(36)      NULL,
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by    VARCHAR(36)      NULL,
  version       BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agt_exit_no (exit_no),
  KEY idx_agt_exit_agent (agent_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理清退单（顺序门禁；同一代理同时只有一张未关闭的）';

CREATE TABLE IF NOT EXISTS agt_ops_assessment (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  agent_no        VARCHAR(36)   NOT NULL,
  period          VARCHAR(7)    NOT NULL COMMENT '考核月 YYYY-MM',
  apply_period    VARCHAR(7)    NOT NULL COMMENT '系数作用月 = 考核月的下一月',
  wo_total        INT           NOT NULL DEFAULT 0 COMMENT '考核月内完结的代理工单数',
  wo_in_sla       INT           NOT NULL DEFAULT 0,
  sla_rate        DECIMAL(6,4)      NULL COMMENT '解决时限达成率；没有工单为空（不参与降档）',
  online_rate     DECIMAL(6,4)      NULL COMMENT '考核时刻名下机柜在线率（快照）',
  complaints      INT           NOT NULL DEFAULT 0 COMMENT '名下站点考核月内客诉单数',
  taken_over      INT           NOT NULL DEFAULT 0 COMMENT '考核月内被平台接管的工单数',
  coefficient     DECIMAL(6,4)  NOT NULL DEFAULT 1 COMMENT '运维分成系数（乘在 OPERATE 分润比例上）',
  computed_at     DATETIME(3)   NOT NULL,
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by      VARCHAR(36)       NULL,
  updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(36)       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agt_ops_assessment (agent_no, period),
  KEY idx_agt_ops_apply (agent_no, apply_period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理运维月度考核（裁决 #1：不逐单扣，按达成率调运维分成系数）';

UPDATE dev_alarm_code SET enabled = 1, eval_type = 'METRIC', hold_minutes = 0,
       suggestion = '约谈代理；次月运维分成系数已按达成率自动下调，连续不达标考虑收回站点'
 WHERE code = 'AGENT_SLA_BELOW';
INSERT INTO dev_alarm_route (alarm_code, cause, disposition, wo_type, priority_delta, fallback) VALUES
  ('AGENT_SLA_BELOW','*','TODO',NULL,0,NULL)
ON DUPLICATE KEY UPDATE disposition = VALUES(disposition), wo_type = VALUES(wo_type), fallback = VALUES(fallback);

ALTER TABLE dev_alarm
  MODIFY COLUMN cause VARCHAR(16) NULL
    COMMENT 'OFFLINE 离线 / UNSTABLE 链路不稳 / NO_STOCK 无宝 / FULL 满柜 / FAULT 故障停用 / MIXED 混合 / NO_CONTRACT 无合同 / EXPIRING 合同将到期 / LOW_BATTERY 低电堆积 / AGED 宝老化 / MISSING 宝失联 / SLA_BELOW 运维不达标 / CANCEL_FAILED 撤单失败 / SN_SEEN 已识别未结单 / HAZARD 安全隐患 / OVERHEAT 过热';

INSERT INTO sys_param (tenant_id, param_key, label, value, group_name) VALUES
  ('MAIN', 'agent.ops.coef.full_rate', '运维分成系数：达成率 ≥ 此值不打折', '0.95', '代理'),
  ('MAIN', 'agent.ops.coef.mid_rate',  '运维分成系数：达成率 ≥ 此值按中档', '0.80', '代理'),
  ('MAIN', 'agent.ops.coef.mid',       '运维分成系数：中档系数', '0.90', '代理'),
  ('MAIN', 'agent.ops.coef.low',       '运维分成系数：低于中档阈值的系数', '0.80', '代理')
ON DUPLICATE KEY UPDATE label = VALUES(label), group_name = VALUES(group_name);

ALTER TABLE sys_outbox
  MODIFY COLUMN event_type VARCHAR(64) NOT NULL
  COMMENT 'ASSET_ASSIGNED/CONTRACT_SIGNED/ORDER_SETTLED/CABINET_WENT_LIVE/DEVICE_SIGNAL/WORK_ORDER_COMPLETED/SITE_CLOSED/AGENT_STATUS_CHANGED';
