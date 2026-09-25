SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 业务告警（2026-09-25 定：告警码业务视角；TDD-运营核心流程/05）
--
-- 设备错误码降为「信号」（dev_event_code，见 V97）；告警中心只放业务告警：
-- 站点借不到 / 还不了 / 付了款没拿到宝 / 无合同在营业 …… 共 9 个业务域、30 个码，L0 十个启用。
--
-- close_reason 以 V90（告警关闭）为基线：RESOLVED / FALSE_ALARM / SELF_HEALED，
-- 本迁移追加 AUTO_FIXED（系统自愈动作成功）/ SUPERSEDED（被上层告警取代）。
--
-- 「同一对象同一码任一时刻最多一条未关闭告警」由数据库保证：open_key 生成列上的唯一键。
-- 只对判定引擎产生的告警（source=EVAL）生效 —— 存量设备告警的 dedup_key 语义不同，不纳入。
-- ============================================================

-- 1) 业务告警码：码即处置预案
ALTER TABLE dev_alarm_code
  ADD COLUMN IF NOT EXISTS domain               VARCHAR(16) NOT NULL DEFAULT 'AVAILABILITY'
    COMMENT 'AVAILABILITY 可借 / RETURNABILITY 可还 / TRANSACTION 交易 / SAFETY 安全 / ASSET 资产 / REVENUE 经营 / SERVICE 履约 / PARTNER 合作 / FUND 资金',
  ADD COLUMN IF NOT EXISTS subject_type         VARCHAR(16) NOT NULL DEFAULT 'CABINET'
    COMMENT 'SITE 站点 / CABINET 机柜 / SLOT 仓位 / ORDER 订单 / USER 用户 / POWERBANK 充电宝 / CONTRACT 合同 / PAYEE 收款方 / AGENT 代理 / WORK_ORDER 工单 / PAYMENT 支付单',
  ADD COLUMN IF NOT EXISTS eval_type            VARCHAR(8)  NOT NULL DEFAULT 'EVENT' COMMENT 'EVENT 事件 / STATE 持续状态 / COUNT 窗口计数 / METRIC 周期指标',
  ADD COLUMN IF NOT EXISTS hold_minutes         INT             NULL COMMENT 'STATE：条件持续多少分钟成立',
  ADD COLUMN IF NOT EXISTS window_minutes       INT             NULL COMMENT 'COUNT：统计窗口',
  ADD COLUMN IF NOT EXISTS threshold            DECIMAL(12,4)   NULL COMMENT '阈值（按码解释）',
  ADD COLUMN IF NOT EXISTS business_hours_only  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '只在站点营业时间内累计',
  ADD COLUMN IF NOT EXISTS base_priority        VARCHAR(8)  NOT NULL DEFAULT 'MEDIUM' COMMENT 'LOW / MEDIUM / HIGH / URGENT（工单优先级词表）',
  ADD COLUMN IF NOT EXISTS impact_adjust        TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '参与影响加成；安全域为 0',
  ADD COLUMN IF NOT EXISTS disposition          VARCHAR(16) NOT NULL DEFAULT 'WORK_ORDER' COMMENT 'AUTO_FIX 系统自愈 / WORK_ORDER 运维工单 / CS_CASE 客服跟进 / TODO 待办 / NOTIFY 仅通知',
  ADD COLUMN IF NOT EXISTS owner_role           VARCHAR(32)     NULL COMMENT '待办 / 客服处置的承接角色码',
  ADD COLUMN IF NOT EXISTS wo_delay_minutes     INT         NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS merge_scope          VARCHAR(8)  NOT NULL DEFAULT 'DEVICE' COMMENT 'DEVICE 同设备 / SITE 同站点 / REGION 同区域当日',
  ADD COLUMN IF NOT EXISTS recover_rule         VARCHAR(16) NOT NULL DEFAULT 'SIGNAL_CLEAR' COMMENT 'SIGNAL_CLEAR 条件消失即恢复 / DISPOSITION_DONE 处置完成才关闭 / NONE 仅人工',
  ADD COLUMN IF NOT EXISTS recover_hold_minutes INT         NOT NULL DEFAULT 10,
  ADD COLUMN IF NOT EXISTS supersedes           VARCHAR(32)     NULL COMMENT '本码成立时取代的下层码',
  ADD COLUMN IF NOT EXISTS enabled              TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '判定开关',
  ADD COLUMN IF NOT EXISTS builtin              TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '内置：域 / 主体 / 判定方式 / 码值不可改，不可归档';

-- 2) 业务告警记录
ALTER TABLE dev_alarm
  MODIFY COLUMN source VARCHAR(16) NOT NULL DEFAULT 'DEVICE' COMMENT 'DEVICE 设备信号 / OTA 固件投放 / RENT 租借流程 / EVAL 判定引擎',
  MODIFY COLUMN close_reason VARCHAR(16) NULL
    COMMENT 'RESOLVED 已解决 / FALSE_ALARM 误报 / SELF_HEALED 自愈 / AUTO_FIXED 系统自愈动作成功 / SUPERSEDED 被上层告警取代；关闭时必填',
  ADD COLUMN IF NOT EXISTS domain            VARCHAR(16)   NULL COMMENT 'AVAILABILITY / RETURNABILITY / TRANSACTION / SAFETY / ASSET / REVENUE / SERVICE / PARTNER / FUND',
  ADD COLUMN IF NOT EXISTS subject_type      VARCHAR(16)   NULL COMMENT 'SITE / CABINET / SLOT / ORDER / USER / POWERBANK / CONTRACT / PAYEE / AGENT / WORK_ORDER / PAYMENT',
  ADD COLUMN IF NOT EXISTS subject_no        VARCHAR(64)   NULL COMMENT '受影响对象业务号',
  ADD COLUMN IF NOT EXISTS cause             VARCHAR(16)   NULL COMMENT 'OFFLINE 离线 / NO_STOCK 无宝 / FULL 满柜 / FAULT 故障停用 / MIXED 混合 / NO_CONTRACT 无合同 / CANCEL_FAILED 撤单失败 / SN_SEEN 已识别未结单 / HAZARD 安全隐患 / OVERHEAT 过热',
  ADD COLUMN IF NOT EXISTS impact_scope      VARCHAR(8)    NULL COMMENT 'SITE 整站 / CABINET 单柜 / SLOT 单仓 / ORDER 单用户 / ENTITY 单个对象',
  ADD COLUMN IF NOT EXISTS impact_period     VARCHAR(8)    NULL COMMENT 'PEAK 高峰 / OPEN 营业 / CLOSED 非营业',
  ADD COLUMN IF NOT EXISTS site_tier         VARCHAR(1)    NULL COMMENT 'A / B / C',
  ADD COLUMN IF NOT EXISTS in_flight_orders  INT           NULL,
  ADD COLUMN IF NOT EXISTS priority          VARCHAR(8)    NULL COMMENT 'LOW / MEDIUM / HIGH / URGENT',
  ADD COLUMN IF NOT EXISTS evidence          VARCHAR(2000) NULL COMMENT '证据 JSON，最多 20 条',
  ADD COLUMN IF NOT EXISTS disposition_type  VARCHAR(16)   NULL COMMENT 'AUTO_FIX / WORK_ORDER / CS_CASE / TODO / NOTIFY',
  ADD COLUMN IF NOT EXISTS disposition_ref   VARCHAR(36)   NULL COMMENT '工单号 / 客服单号 / 待办号',
  ADD COLUMN IF NOT EXISTS first_occurred_at DATETIME(3)   NULL,
  ADD COLUMN IF NOT EXISTS last_occurred_at  DATETIME(3)   NULL,
  ADD COLUMN IF NOT EXISTS due_at            DATETIME(3)   NULL COMMENT '计划处置时刻',
  ADD COLUMN IF NOT EXISTS recovered_at      DATETIME(3)   NULL COMMENT '恢复信号时刻（防抖期起点）',
  ADD COLUMN IF NOT EXISTS parent_alarm_no   VARCHAR(36)   NULL COMMENT '被取代时指向上层告警',
  ADD COLUMN IF NOT EXISTS open_key VARCHAR(128)
      AS (CASE WHEN source = 'EVAL' AND status IN ('OPEN', 'ACKED') THEN dedup_key END) STORED;

ALTER TABLE dev_alarm
  ADD UNIQUE KEY IF NOT EXISTS uk_alarm_open (open_key),
  ADD KEY IF NOT EXISTS idx_alarm_due (status, due_at),
  ADD KEY IF NOT EXISTS idx_alarm_recover (status, recovered_at),
  ADD KEY IF NOT EXISTS idx_alarm_domain (domain, status, occurred_at),
  ADD KEY IF NOT EXISTS idx_alarm_disposition (disposition_ref);

-- 3) 根因路由
CREATE TABLE IF NOT EXISTS dev_alarm_route (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  alarm_code      VARCHAR(32) NOT NULL,
  cause           VARCHAR(16) NOT NULL COMMENT '同 dev_alarm.cause；* 兜底',
  disposition     VARCHAR(16) NOT NULL COMMENT 'AUTO_FIX / WORK_ORDER / CS_CASE / TODO / NOTIFY',
  wo_type         VARCHAR(16)     NULL COMMENT 'FAULT / REFILL / INSPECT / INSTALL / REMOVE / COMPLAINT / CLEAN',
  priority_delta  INT         NOT NULL DEFAULT 0,
  fallback        VARCHAR(16)     NULL COMMENT '自愈失败后转：CS_CASE / TODO / WORK_ORDER',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by      VARCHAR(36)     NULL,
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(36)     NULL,
  version         BIGINT      NOT NULL DEFAULT 0,
  deleted         TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_route (alarm_code, cause)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务告警根因路由（全局）';

-- 4) 持续条件（工作表，可随时清空重建）
CREATE TABLE IF NOT EXISTS dev_alarm_condition (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dedup_key       VARCHAR(128) NOT NULL,
  alarm_code      VARCHAR(32)  NOT NULL,
  subject_site    VARCHAR(36)      NULL COMMENT '所属站点（按站点批次清理用）',
  first_seen_at   DATETIME(3)  NOT NULL,
  last_seen_at    DATETIME(3)  NOT NULL,
  held_minutes    INT          NOT NULL DEFAULT 0 COMMENT '计入的持续分钟（营业时间外不计）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_cond (dedup_key),
  KEY idx_alarm_cond_code (alarm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警持续条件（工作表）';

-- 5) 告警时间线（追加）
CREATE TABLE IF NOT EXISTS dev_alarm_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  alarm_no    VARCHAR(36)  NOT NULL,
  event       VARCHAR(16)  NOT NULL COMMENT 'OPEN 成立 / BUMP 重复 / IMPACT_UP 影响升级 / ACK 确认 / DISPOSE 处置 / FIX_TRY 自愈尝试 / FIX_FAIL 自愈失败 / RECOVER 恢复中 / RELAPSE 复发 / CLOSE 关闭 / SUPERSEDE 被取代',
  note        VARCHAR(512)     NULL,
  operator    VARCHAR(36)  NOT NULL COMMENT '员工号或 SYSTEM',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by  VARCHAR(36)      NULL,
  PRIMARY KEY (id),
  KEY idx_alarm_log (alarm_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警时间线（追加）';

-- 6) 告警待办
CREATE TABLE IF NOT EXISTS dev_alarm_todo (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  todo_no       VARCHAR(36)  NOT NULL COMMENT '业务键 TD*',
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  alarm_no      VARCHAR(36)  NOT NULL,
  role_code     VARCHAR(32)  NOT NULL COMMENT '承接角色',
  assignee_no   VARCHAR(36)      NULL COMMENT '具体承接人',
  title         VARCHAR(256) NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 待处理 / DONE 已完成 / CANCELLED 已撤销',
  done_at       DATETIME(3)      NULL,
  done_by       VARCHAR(36)      NULL,
  done_note     VARCHAR(512)     NULL,
  site_no       VARCHAR(36)      NULL,
  agent_no      VARCHAR(36)      NULL,
  open_alarm_no VARCHAR(36) AS (CASE WHEN status = 'OPEN' THEN alarm_no END) STORED,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by    VARCHAR(36)      NULL,
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by    VARCHAR(36)      NULL,
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_todo_no (todo_no),
  UNIQUE KEY uk_todo_open (open_alarm_no),
  KEY idx_todo_role (role_code, status),
  KEY idx_todo_scope (tenant_id, agent_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警待办';

-- 7) 站点画像（派生，可重算）
CREATE TABLE IF NOT EXISTS dev_alarm_site_profile (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  site_no      VARCHAR(36)   NOT NULL,
  tier         VARCHAR(1)    NOT NULL COMMENT 'A / B / C',
  peak_hours   VARCHAR(32)       NULL COMMENT '高峰小时（业务时区），逗号分隔',
  gmv_30d      DECIMAL(18,2)     NULL,
  computed_at  DATETIME(3)   NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_profile (site_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警用站点画像（派生）';

-- 8) 旧设备码退役（降为信号，见 dev_event_code）；存量告警行补业务域
UPDATE dev_alarm_code SET archived_at = COALESCE(archived_at, CURRENT_TIMESTAMP(3)), enabled = 0 WHERE code IN ('E001', 'E002', 'E003', 'E004');
UPDATE dev_alarm SET domain = 'AVAILABILITY', subject_type = 'CABINET', subject_no = cabinet_no WHERE domain IS NULL;

-- 9) 30 个业务码（L0 十个 enabled=1；支付类依赖未就绪，出厂关闭）
INSERT INTO dev_alarm_code
  (code, message, message_en, level, suggestion, auto_work_order, domain, subject_type, eval_type, hold_minutes, window_minutes, threshold,
   business_hours_only, base_priority, impact_adjust, disposition, owner_role, wo_delay_minutes, merge_scope, recover_rule, recover_hold_minutes, supersedes, enabled, builtin)
VALUES
  ('SITE_UNRENTABLE','站点借不到','Site unrentable','CRITICAL','整站没有可借的宝：离线先联系场地查电源网络，无宝安排补货',1,'AVAILABILITY','SITE','STATE',10,NULL,NULL,1,'HIGH',1,'WORK_ORDER',NULL,0,'SITE','SIGNAL_CLEAR',10,'CABINET_UNRENTABLE',1,1),
  ('CABINET_UNRENTABLE','单柜借不到','Cabinet unrentable','WARN','该柜无可借宝：离线查网络供电，无宝补货，停借查故障',1,'AVAILABILITY','CABINET','STATE',10,NULL,NULL,1,'MEDIUM',1,'WORK_ORDER',NULL,30,'DEVICE','SIGNAL_CLEAR',10,NULL,1,1),
  ('RENTABLE_LOW','可借不足','Low rentable stock','INFO','纳入补货路线',1,'AVAILABILITY','SITE','STATE',60,NULL,2,1,'LOW',1,'WORK_ORDER',NULL,0,'REGION','SIGNAL_CLEAR',10,NULL,0,1),
  ('SITE_UNRETURNABLE','站点还不了','Site unreturnable','CRITICAL','整站无空仓：满柜取宝，离线或锁故障派维修；C 端已提示最近可还站点',1,'RETURNABILITY','SITE','STATE',10,NULL,NULL,1,'HIGH',1,'WORK_ORDER',NULL,0,'SITE','SIGNAL_CLEAR',10,'CABINET_UNRETURNABLE',1,1),
  ('CABINET_UNRETURNABLE','单柜还不了','Cabinet unreturnable','WARN','该柜无空仓或离线：满柜取宝，故障维修',1,'RETURNABILITY','CABINET','STATE',10,NULL,NULL,1,'MEDIUM',1,'WORK_ORDER',NULL,30,'DEVICE','SIGNAL_CLEAR',10,NULL,1,1),
  ('RETURN_SPACE_LOW','可还位不足','Low return space','INFO','纳入取宝路线',1,'RETURNABILITY','SITE','STATE',60,NULL,1,1,'LOW',1,'WORK_ORDER',NULL,0,'REGION','SIGNAL_CLEAR',10,NULL,0,1),
  ('RENT_NOT_DELIVERED','付了款没拿到宝','Paid but not delivered','CRITICAL','系统自动撤单并释放预授权；失败转客服主动联系用户',0,'TRANSACTION','ORDER','STATE',5,NULL,NULL,0,'HIGH',1,'AUTO_FIX','CS',0,'DEVICE','SIGNAL_CLEAR',0,NULL,1,1),
  ('RETURN_NOT_RECOGNIZED','还了但没被认','Return not recognized','CRITICAL','系统按宝首次在柜中上报的时刻结束订单；失败转客服',0,'TRANSACTION','ORDER','STATE',5,NULL,NULL,0,'HIGH',1,'AUTO_FIX','CS',0,'DEVICE','SIGNAL_CLEAR',0,NULL,1,1),
  ('RETURN_DISPUTED','用户称已还、系统无记录','Return disputed','WARN','客服跟进，并查该站点当时的仓位上报',0,'TRANSACTION','ORDER','EVENT',NULL,NULL,NULL,0,'MEDIUM',1,'CS_CASE','CS',0,'DEVICE','DISPOSITION_DONE',0,NULL,0,1),
  ('EJECT_FAIL_RATE','某柜频繁弹不出','Frequent eject failure','CRITICAL','该柜停借并派维修',1,'TRANSACTION','CABINET','COUNT',NULL,60,3,0,'HIGH',1,'WORK_ORDER',NULL,0,'DEVICE','DISPOSITION_DONE',0,NULL,0,1),
  ('BILLING_ANOMALY','计费异常','Billing anomaly','WARN','财务核查订单金额与计费方案',0,'TRANSACTION','ORDER','EVENT',NULL,NULL,NULL,0,'MEDIUM',1,'TODO','FINANCE',0,'DEVICE','DISPOSITION_DONE',0,NULL,0,1),
  ('BATTERY_HAZARD','充电宝有安全隐患','Battery hazard','CRITICAL','已锁仓；立即派人现场处置，通知场地联系人',1,'SAFETY','SLOT','EVENT',NULL,NULL,NULL,0,'URGENT',0,'WORK_ORDER',NULL,0,'DEVICE','DISPOSITION_DONE',0,NULL,1,1),
  ('CABINET_OVERHEAT','机柜过热','Cabinet overheat','CRITICAL','已降功率并停借；立即派人现场检查散热',1,'SAFETY','CABINET','EVENT',NULL,NULL,NULL,0,'URGENT',0,'WORK_ORDER',NULL,0,'DEVICE','DISPOSITION_DONE',0,NULL,1,1),
  ('HAZARD_IN_USE','隐患宝在用户手中','Hazard powerbank in use','CRITICAL','客服联系用户免费归还',0,'SAFETY','ORDER','EVENT',NULL,NULL,NULL,0,'URGENT',0,'CS_CASE','CS',0,'DEVICE','DISPOSITION_DONE',0,NULL,0,1),
  ('OVERDUE_CLUSTER','异常逾期聚集','Overdue cluster','WARN','风控核查，必要时冻结借出资格',0,'ASSET','USER','METRIC',NULL,1440,2,0,'MEDIUM',1,'TODO','CS',0,'DEVICE','DISPOSITION_DONE',0,NULL,0,1),
  ('POWERBANK_MISSING','充电宝失联','Powerbank missing','WARN','仓管核查最后位置',0,'ASSET','POWERBANK','STATE',1440,NULL,NULL,0,'MEDIUM',1,'TODO','OPS',0,'DEVICE','SIGNAL_CLEAR',0,NULL,0,1),
  ('STOCKTAKE_DIFF','盘点差异','Stocktake difference','WARN','仓管逐件追查差异',0,'ASSET','SITE','EVENT',NULL,NULL,NULL,0,'MEDIUM',1,'TODO','OPS',0,'DEVICE','DISPOSITION_DONE',0,NULL,0,1),
  ('CABINET_RELOCATED','设备被挪位','Cabinet relocated','WARN','现场确认并重新绑定或复位',1,'ASSET','CABINET','EVENT',NULL,NULL,NULL,0,'MEDIUM',1,'WORK_ORDER',NULL,0,'DEVICE','DISPOSITION_DONE',0,NULL,0,1),
  ('SITE_ZERO_ORDER','营业中零订单','Zero orders while open','WARN','到现场看是否被挪位、遮挡、二维码损坏',1,'REVENUE','SITE','METRIC',NULL,4320,0,1,'MEDIUM',1,'WORK_ORDER',NULL,0,'SITE','SIGNAL_CLEAR',0,NULL,0,1),
  ('SITE_REVENUE_DROP','站点收入骤降','Site revenue drop','INFO','核查场地变化、竞品进驻、摆放位置',0,'REVENUE','SITE','METRIC',NULL,10080,0.5,0,'LOW',1,'TODO','BD',0,'DEVICE','SIGNAL_CLEAR',0,NULL,0,1),
  ('SITE_LOW_YIELD','低效站点','Low-yield site','INFO','迁机或撤场评估',0,'REVENUE','SITE','METRIC',NULL,NULL,NULL,0,'LOW',1,'TODO','BD',0,'DEVICE','SIGNAL_CLEAR',0,NULL,0,1),
  ('ALARM_UNATTENDED','告警无人处理','Alarm unattended','CRITICAL','升级通知运维主管',0,'SERVICE','WORK_ORDER','STATE',30,NULL,NULL,0,'HIGH',1,'NOTIFY','OPS',0,'DEVICE','SIGNAL_CLEAR',0,NULL,0,1),
  ('WO_SLA_BREACH','工单超时','Work order SLA breach','WARN','升级通知；代理的单可由平台接管',0,'SERVICE','WORK_ORDER','STATE',0,NULL,NULL,0,'MEDIUM',1,'NOTIFY','OPS',0,'DEVICE','SIGNAL_CLEAR',0,NULL,0,1),
  ('SITE_OWNER_MISSING','站点无人运维','Site owner missing','WARN','指定运维责任人，否则自动工单派不出去',0,'SERVICE','SITE','STATE',0,NULL,NULL,0,'MEDIUM',1,'TODO','OPS',0,'DEVICE','SIGNAL_CLEAR',0,NULL,0,1),
  ('AGENT_SLA_BELOW','代理运维不达标','Agent SLA below target','INFO','约谈、调整分润系数或收回站点',0,'SERVICE','AGENT','METRIC',NULL,43200,0.8,0,'LOW',1,'TODO','BD',0,'DEVICE','SIGNAL_CLEAR',0,NULL,0,1),
  ('CONTRACT_EXPIRING','合同即将到期','Contract expiring','INFO','续签或撤场评估',0,'PARTNER','CONTRACT','STATE',0,NULL,60,0,'LOW',1,'TODO','BD',0,'DEVICE','SIGNAL_CLEAR',0,NULL,0,1),
  ('SITE_WITHOUT_CONTRACT','无合同在营业','Site without contract','CRITICAL','补签合同或发起撤场；站点不自动停业',0,'PARTNER','SITE','STATE',0,NULL,NULL,0,'HIGH',1,'TODO','BD',0,'DEVICE','SIGNAL_CLEAR',0,NULL,1,1),
  ('PAYEE_NOT_READY','分成方收不了款','Payee not ready','WARN','补收款账户',0,'PARTNER','PAYEE','STATE',0,NULL,NULL,0,'MEDIUM',1,'TODO','FINANCE',0,'DEVICE','SIGNAL_CLEAR',0,NULL,0,1),
  ('PAYMENT_STATE_UNKNOWN','支付结果不明','Payment state unknown','CRITICAL','系统回查通道；仍不明转财务',0,'FUND','PAYMENT','STATE',10,NULL,NULL,0,'HIGH',1,'AUTO_FIX','FINANCE',0,'DEVICE','SIGNAL_CLEAR',0,NULL,0,1),
  ('REFUND_FAILED','退款失败','Refund failed','WARN','财务处理，并通知客服',0,'FUND','PAYMENT','EVENT',NULL,NULL,NULL,0,'HIGH',1,'TODO','FINANCE',0,'DEVICE','DISPOSITION_DONE',0,NULL,0,1),
  ('PAYOUT_FAILED','打款失败','Payout failed','WARN','财务重新打款',0,'FUND','PAYMENT','EVENT',NULL,NULL,NULL,0,'MEDIUM',1,'TODO','FINANCE',0,'DEVICE','DISPOSITION_DONE',0,NULL,0,1),
  ('SHARE_NOT_GENERATED','分润未生成','Share not generated','WARN','财务 / 技术核查分润生成',0,'FUND','ORDER','STATE',60,NULL,NULL,0,'MEDIUM',1,'TODO','FINANCE',0,'DEVICE','SIGNAL_CLEAR',0,NULL,0,1)
ON DUPLICATE KEY UPDATE message = VALUES(message), message_en = VALUES(message_en), level = VALUES(level), suggestion = VALUES(suggestion),
  domain = VALUES(domain), subject_type = VALUES(subject_type), eval_type = VALUES(eval_type), builtin = VALUES(builtin);

-- 10) L0 根因路由
INSERT INTO dev_alarm_route (alarm_code, cause, disposition, wo_type, priority_delta, fallback) VALUES
  ('SITE_UNRENTABLE','OFFLINE','WORK_ORDER','FAULT',0,NULL),('SITE_UNRENTABLE','NO_STOCK','WORK_ORDER','REFILL',0,NULL),
  ('SITE_UNRENTABLE','FAULT','WORK_ORDER','FAULT',0,NULL),('SITE_UNRENTABLE','MIXED','WORK_ORDER','FAULT',0,NULL),
  ('CABINET_UNRENTABLE','OFFLINE','WORK_ORDER','FAULT',0,NULL),('CABINET_UNRENTABLE','NO_STOCK','WORK_ORDER','REFILL',0,NULL),
  ('CABINET_UNRENTABLE','FAULT','WORK_ORDER','FAULT',0,NULL),('CABINET_UNRENTABLE','MIXED','WORK_ORDER','FAULT',0,NULL),
  ('SITE_UNRETURNABLE','FULL','WORK_ORDER','REFILL',0,NULL),('SITE_UNRETURNABLE','OFFLINE','WORK_ORDER','FAULT',0,NULL),
  ('SITE_UNRETURNABLE','FAULT','WORK_ORDER','FAULT',0,NULL),('SITE_UNRETURNABLE','MIXED','WORK_ORDER','FAULT',0,NULL),
  ('CABINET_UNRETURNABLE','FULL','WORK_ORDER','REFILL',0,NULL),('CABINET_UNRETURNABLE','OFFLINE','WORK_ORDER','FAULT',0,NULL),
  ('CABINET_UNRETURNABLE','FAULT','WORK_ORDER','FAULT',0,NULL),('CABINET_UNRETURNABLE','MIXED','WORK_ORDER','FAULT',0,NULL),
  ('RENT_NOT_DELIVERED','*','AUTO_FIX',NULL,0,'CS_CASE'),('RETURN_NOT_RECOGNIZED','*','AUTO_FIX',NULL,0,'CS_CASE'),
  ('BATTERY_HAZARD','*','WORK_ORDER','FAULT',0,NULL),('CABINET_OVERHEAT','*','WORK_ORDER','FAULT',0,NULL),
  ('SITE_WITHOUT_CONTRACT','*','TODO',NULL,0,NULL)
ON DUPLICATE KEY UPDATE disposition = VALUES(disposition), wo_type = VALUES(wo_type), fallback = VALUES(fallback);
