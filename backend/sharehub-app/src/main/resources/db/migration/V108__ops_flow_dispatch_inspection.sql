SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 批次 D：调度与巡检（对齐清单 §七 D1–D5）
--
--   D1 缺宝 / 满柜阈值调度：启用 RENTABLE_LOW / RETURN_SPACE_LOW（判定改为逐柜：可借 1..N 或可借率 < 20% / 空仓 1..N；
--      可借 / 空仓为 0 的柜子仍归「借不到 / 还不了」告警）
--   D2 低电宝堆积（LOW_BATTERY_PILEUP → 查仓位充电维修单）· 宝老化（每日任务标 AGED、不再借出；POWERBANK_AGED → 换宝回收单）
--   D3 补宝 / 取宝（REFILL）工单一律按「区域 × 当天」合并成一张调度单，多站点明细 = 挂靠的告警
--   D4 巡检派生维修单：wo_order.source 增 INSPECTION，source_ref 记巡检单号
--   D5 巡检清点：巡检完工填清点宝数，与系统在柜数不符落资产差异（source_type 增 INSPECTION）
-- ============================================================

-- —— 词表 ——
ALTER TABLE dev_alarm
  MODIFY COLUMN cause VARCHAR(16) NULL
    COMMENT 'OFFLINE 离线 / NO_STOCK 无宝 / FULL 满柜 / FAULT 故障停用 / MIXED 混合 / NO_CONTRACT 无合同 / EXPIRING 合同将到期 / LOW_BATTERY 低电堆积 / AGED 宝老化 / CANCEL_FAILED 撤单失败 / SN_SEEN 已识别未结单 / HAZARD 安全隐患 / OVERHEAT 过热';

ALTER TABLE dev_powerbank
  MODIFY COLUMN health VARCHAR(8) NOT NULL DEFAULT 'OK' COMMENT 'OK 正常 / FAULT 故障 / AGED 老化待报废（循环次数超限，不再借出）';

ALTER TABLE wo_order
  MODIFY COLUMN source VARCHAR(16) NOT NULL COMMENT 'ALERT/USER/VENUE/MANUAL/PLAN/INSPECTION';

ALTER TABLE inv_asset_diff
  MODIFY COLUMN source_type VARCHAR(16) NOT NULL COMMENT 'TRANSFER 调拨签收 / REMOVAL 撤机清点 / INSPECTION 巡检清点';

-- —— D1 / D2 告警码 ——
UPDATE dev_alarm_code SET enabled = 1, threshold = NULL, hold_minutes = 30,
       suggestion = '纳入当天区域补宝路线（同区域同日合并成一张调度单）'
 WHERE code = 'RENTABLE_LOW';
UPDATE dev_alarm_code SET enabled = 1, threshold = NULL, hold_minutes = 30,
       suggestion = '纳入当天区域取宝路线（同区域同日合并成一张调度单）'
 WHERE code = 'RETURN_SPACE_LOW';

INSERT INTO dev_alarm_code
  (code, message, message_en, level, suggestion, auto_work_order, domain, subject_type, eval_type, hold_minutes, window_minutes, threshold,
   business_hours_only, base_priority, impact_adjust, disposition, owner_role, wo_delay_minutes, merge_scope, recover_rule, recover_hold_minutes, supersedes, enabled, builtin)
VALUES
  ('LOW_BATTERY_PILEUP','低电宝堆积','Low-battery pile-up','WARN','多半是仓位充电故障：到现场查充电触点与供电',1,'AVAILABILITY','CABINET','STATE',60,NULL,NULL,0,'MEDIUM',1,'WORK_ORDER',NULL,0,'DEVICE','SIGNAL_CLEAR',10,NULL,1,1),
  ('POWERBANK_AGED','充电宝老化待报废','Aged power banks','INFO','已停止借出；随当天区域补宝路线换下回收',1,'ASSET','CABINET','STATE',0,NULL,NULL,0,'LOW',0,'WORK_ORDER',NULL,0,'REGION','SIGNAL_CLEAR',0,NULL,1,1)
ON DUPLICATE KEY UPDATE message = VALUES(message), message_en = VALUES(message_en), level = VALUES(level), suggestion = VALUES(suggestion),
  domain = VALUES(domain), subject_type = VALUES(subject_type), eval_type = VALUES(eval_type), builtin = VALUES(builtin);

INSERT INTO dev_alarm_route (alarm_code, cause, disposition, wo_type, priority_delta, fallback) VALUES
  ('RENTABLE_LOW','*','WORK_ORDER','REFILL',0,NULL),
  ('RETURN_SPACE_LOW','*','WORK_ORDER','REFILL',0,NULL),
  ('LOW_BATTERY_PILEUP','*','WORK_ORDER','FAULT',0,NULL),
  ('POWERBANK_AGED','*','WORK_ORDER','REFILL',0,NULL)
ON DUPLICATE KEY UPDATE disposition = VALUES(disposition), wo_type = VALUES(wo_type), fallback = VALUES(fallback);

-- —— 系统参数 ——
INSERT INTO sys_param (tenant_id, param_key, label, value, group_name) VALUES
  ('MAIN', 'dispatch.refill.max_rentable', '补宝调度：单柜可借数 ≤ N 触发', '1', '调度'),
  ('MAIN', 'dispatch.refill.min_ratio',    '补宝调度：单柜可借率低于此值触发', '0.2', '调度'),
  ('MAIN', 'dispatch.pickup.max_empty',    '取宝调度：单柜空仓数 ≤ N 触发', '1', '调度'),
  ('MAIN', 'dispatch.low_battery.ratio',   '低电宝堆积：在柜宝中低电占比达到此值触发', '0.5', '调度'),
  ('MAIN', 'device.powerbank.retire_cycles', '充电宝循环次数超过此值标记老化待报废', '500', '设备')
ON DUPLICATE KEY UPDATE label = VALUES(label), group_name = VALUES(group_name);
