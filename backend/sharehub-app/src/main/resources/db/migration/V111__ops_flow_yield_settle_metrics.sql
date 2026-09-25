SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 批次 G：效益、结算与指标（对齐清单 §七 G1–G5）
--
--   G1 低效站点：启用 SITE_LOW_YIELD（连续 N 个整月单柜日均收入低于 threshold（AED）→ BD 待办：迁机或撤场评估）
--   G2 保底补差：保底 + 分成合同，出账时按账期补齐「保底 − 场地方分成」差额，作结算调整项（GUARANTEE_TOPUP）
--   G3 对账单：结构化数据 + 可打印对账单（无表结构变化）
--   G4 工单成本：完工记配件 / 人工金额；成本归属 = 代理运维的单归代理，其余归站点
--   G5 运营指标：只读统计（无表结构变化）
-- ============================================================

-- —— G1 ——
UPDATE dev_alarm_code SET enabled = 1, threshold = 5, hold_minutes = 0, eval_type = 'METRIC',
       suggestion = '迁机或撤场评估：先看摆放位置、场地客流与竞品，再决定迁到高效站点还是发起撤场'
 WHERE code = 'SITE_LOW_YIELD';
INSERT INTO dev_alarm_route (alarm_code, cause, disposition, wo_type, priority_delta, fallback) VALUES
  ('SITE_LOW_YIELD','*','TODO',NULL,0,NULL)
ON DUPLICATE KEY UPDATE disposition = VALUES(disposition), wo_type = VALUES(wo_type), fallback = VALUES(fallback);

ALTER TABLE dev_alarm
  MODIFY COLUMN cause VARCHAR(16) NULL
    COMMENT 'OFFLINE 离线 / UNSTABLE 链路不稳 / NO_STOCK 无宝 / FULL 满柜 / FAULT 故障停用 / MIXED 混合 / NO_CONTRACT 无合同 / EXPIRING 合同将到期 / LOW_BATTERY 低电堆积 / AGED 宝老化 / MISSING 宝失联 / SLA_BELOW 运维不达标 / LOW_YIELD 低效 / CANCEL_FAILED 撤单失败 / SN_SEEN 已识别未结单 / HAZARD 安全隐患 / OVERHEAT 过热';

-- —— G2 ——
ALTER TABLE stl_adjustment
  ADD COLUMN IF NOT EXISTS period VARCHAR(7) NOT NULL DEFAULT '' COMMENT '按账期的调整（保底补差）填 YYYY-MM；一次性的（撤场结清）为空串',
  MODIFY COLUMN kind VARCHAR(24) NOT NULL COMMENT 'DEPOSIT_REFUND 押金退还 / ENTRY_FEE_SETTLE 进场费结清 / GUARANTEE_TOPUP 保底补差',
  MODIFY COLUMN source VARCHAR(24) NOT NULL COMMENT 'SITE_CLOSED 撤场关闭 / GUARANTEE 保底合同出账';
ALTER TABLE stl_adjustment DROP INDEX IF EXISTS uk_stl_adjustment_src;
ALTER TABLE stl_adjustment ADD UNIQUE KEY uk_stl_adjustment_src (source, site_no, contract_no, kind, period);

-- —— G4 ——
ALTER TABLE wo_handle
  ADD COLUMN IF NOT EXISTS part_cost  DECIMAL(18,2) NULL COMMENT '配件金额（完工时填）',
  ADD COLUMN IF NOT EXISTS labor_cost DECIMAL(18,2) NULL COMMENT '人工金额（完工时填）';
ALTER TABLE wo_order
  ADD COLUMN IF NOT EXISTS cost_total       DECIMAL(18,2) NULL COMMENT '工单成本（配件 + 人工，完工累计）',
  ADD COLUMN IF NOT EXISTS cost_currency    VARCHAR(8)    NULL,
  ADD COLUMN IF NOT EXISTS cost_bearer_type VARCHAR(16)   NULL COMMENT 'SITE 计入站点效益 / AGENT 计入代理（代理运维的单）',
  ADD COLUMN IF NOT EXISTS cost_bearer_no   VARCHAR(36)   NULL COMMENT '站点号 / 代理号';

INSERT INTO sys_param (tenant_id, param_key, label, value, group_name) VALUES
  ('MAIN', 'site.low_yield.months', '低效站点：连续 N 个整月低于线才预警', '2', '站点')
ON DUPLICATE KEY UPDATE label = VALUES(label), group_name = VALUES(group_name);
