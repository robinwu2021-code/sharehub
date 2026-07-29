-- ============================================================
-- powerbank · pb_core 建表：v2 ops 子域增量 —— 告警 dev_alarm* · 设备编码 · 库存 inv_ · 场地 loc_ · 代理 agt_ · 工单 SLA 规则
-- 对齐 db-design.md v2 §三(3.1~3.6) / §1.3~§1.7 / §十 索引基线
-- MySQL 8 · InnoDB · utf8mb4_0900_ai_ci
-- BaseEntity: id/region_id/created_at/updated_at/version/deleted；tenant_id 默认 MAIN(ADR-011)
-- 约定：id 库内物理主键；<x>_no 全局业务键(UK)；跨域逻辑引用不建物理 FK，仅索引。
--       金额 DECIMAL(18,2)+currency；小数比率 DECIMAL(5,4) 0..1；百分数 DECIMAL(5,2) 0..100；
--       仅日期 DATE；时刻 CHAR(5) HH:mm；账期 CHAR(7) YYYY-MM；三语文本 xxx/xxx_en/xxx_ar 三列；
--       枚举 = VARCHAR + 列 COMMENT 列全部合法值（不用 MySQL ENUM）；
--       append 表（dev_alarm_notice / agt_assignment / loc_site_lifecycle_log）无 version/deleted。
-- 已存在不重建：agt_account（见 pb_core-loc-agt-iam.sql）；dev_alert（本文件用 dev_alarm 取代，迁移草稿见文末）
-- ============================================================
SET NAMES utf8mb4;

-- ============================================================
-- 3.2 告警 dev_alarm*（菜单：告警管理 4 叶）—— v1 dev_alert 改造并更名
-- ============================================================

-- 告警记录（聚合根）
CREATE TABLE dev_alarm (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  alarm_no          VARCHAR(36)  NOT NULL                COMMENT '业务键 ALM*',
  tenant_id         VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id         VARCHAR(36)      NULL,
  cabinet_no        VARCHAR(36)      NULL                COMMENT '告警机柜(逻辑引用)',
  site_no           VARCHAR(36)      NULL                COMMENT '站点归属(冗余·数据权限)',
  agent_no          VARCHAR(36)      NULL                COMMENT '归属代理(冗余·数据权限 ADR-012)',
  vendor_code       VARCHAR(32)      NULL                COMMENT '供应商',
  alarm_code        VARCHAR(32)  NOT NULL                COMMENT '平台统一码(→dev_alarm_code.code)',
  vendor_error_code VARCHAR(32)      NULL                COMMENT '厂商原始错误码(归一化前)',
  level             VARCHAR(16)  NOT NULL DEFAULT 'WARN' COMMENT 'INFO/WARN/CRITICAL',
  source            VARCHAR(16)  NOT NULL DEFAULT 'DEVICE' COMMENT 'DEVICE/OTA/RENT',
  occurred_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时刻(列表默认排序)',
  status            VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ACKED/CLOSED',
  wo_no             VARCHAR(36)      NULL                COMMENT '转工单(幂等落在 wo_order.source_ref UK)',
  remark            VARCHAR(512)     NULL                COMMENT '处置备注',
  dedup_key         VARCHAR(128)     NULL                COMMENT '去重键(同源重复合并计数)',
  count             INT          NOT NULL DEFAULT 1      COMMENT '合并次数',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version           BIGINT       NOT NULL DEFAULT 0,
  deleted           TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_no (alarm_no),
  KEY idx_alarm_scope (tenant_id, agent_no, status, occurred_at) COMMENT '数据范围热路径(db-design §十)',
  KEY idx_alarm_cabinet (cabinet_no),
  KEY idx_alarm_site (site_no),
  KEY idx_alarm_code (alarm_code),
  KEY idx_alarm_wo (wo_no),
  KEY idx_alarm_dedup (dedup_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警记录(聚合根,v1 dev_alert 改造更名)';

-- 告警通知流水（append，无 version/deleted）
CREATE TABLE dev_alarm_notice (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  notice_no   VARCHAR(36)  NOT NULL                COMMENT '业务键 AN*',
  tenant_id   VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  alarm_no    VARCHAR(36)  NOT NULL                COMMENT '所属告警',
  channel     VARCHAR(16)  NOT NULL                COMMENT 'SMS/EMAIL/PUSH/WEBHOOK',
  target      VARCHAR(128)     NULL                COMMENT '接收方(存储即脱敏)',
  sent_at     DATETIME(3)      NULL                COMMENT '发送时刻(空=尚未发生)',
  status      VARCHAR(16)  NOT NULL DEFAULT 'SENT' COMMENT 'SENT/FAILED',
  fail_reason VARCHAR(256)     NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_notice_no (notice_no),
  KEY idx_alarm_notice_alarm (alarm_no),
  KEY idx_alarm_notice_time (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警通知流水(append)';

-- 告警代码字典（全局表，不注入 tenant_id）—— 字典即处置预案
CREATE TABLE dev_alarm_code (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code            VARCHAR(32)  NOT NULL                COMMENT '平台统一告警码(自然键)',
  message         VARCHAR(256) NOT NULL                COMMENT '描述(中文)',
  message_en      VARCHAR(256)     NULL                COMMENT '描述(英文)',
  message_ar      VARCHAR(256)     NULL                COMMENT '描述(阿语)',
  level           VARCHAR(16)  NOT NULL DEFAULT 'WARN' COMMENT 'INFO/WARN/CRITICAL',
  suggestion      VARCHAR(512)     NULL                COMMENT '建议处置(预案)',
  auto_work_order TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否自动开工单',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version         BIGINT       NOT NULL DEFAULT 0,
  deleted         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_code (code),
  KEY idx_alarm_code_level (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警代码字典(全局)';

-- 告警通知规则（静默窗口 + 升级策略）
CREATE TABLE dev_alarm_rule (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  rule_no          VARCHAR(36)  NOT NULL                COMMENT '业务键 AR*',
  tenant_id        VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  alarm_code       VARCHAR(32)      NULL                COMMENT '匹配告警码(空=全部)',
  target           VARCHAR(128)     NULL                COMMENT '接收方(角色/手机号/webhook)',
  channel          VARCHAR(16)  NOT NULL                COMMENT 'SMS/EMAIL/PUSH/WEBHOOK',
  method           VARCHAR(16)  NOT NULL DEFAULT 'INSTANT' COMMENT 'INSTANT/DIGEST',
  quiet_start      CHAR(5)          NULL                COMMENT '静默窗口开始 HH:mm',
  quiet_end        CHAR(5)          NULL                COMMENT '静默窗口结束 HH:mm',
  escalate_minutes INT              NULL                COMMENT '未处置 N 分钟后升级(空=不升级)',
  status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version          BIGINT       NOT NULL DEFAULT 0,
  deleted          TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_rule_no (rule_no),
  KEY idx_alarm_rule_tenant (tenant_id, status),
  KEY idx_alarm_rule_code (alarm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警通知规则';

-- ============================================================
-- 3.1 设备编码批次 dev_code_batch（菜单：设备编码）
-- ============================================================
CREATE TABLE dev_code_batch (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_no    VARCHAR(36)  NOT NULL                COMMENT '业务键 BC*',
  tenant_id   VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  vendor_code VARCHAR(32)      NULL                COMMENT '供应商',
  code_type   VARCHAR(16)  NOT NULL DEFAULT 'QR'   COMMENT 'QR/SN',
  range_start VARCHAR(64)  NOT NULL                COMMENT '编码区间起',
  range_end   VARCHAR(64)  NOT NULL                COMMENT '编码区间止',
  total       INT          NOT NULL DEFAULT 0      COMMENT '批次总量',
  bound       INT          NOT NULL DEFAULT 0      COMMENT '已绑定数量',
  produced_at DATE             NULL                COMMENT '生产日期(仅日期)',
  status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PARTIAL/BOUND/VOID',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version     BIGINT       NOT NULL DEFAULT 0,
  deleted     TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code_batch_no (batch_no),
  KEY idx_code_batch_tenant (tenant_id, status),
  KEY idx_code_batch_vendor (vendor_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备编码批次';

-- ============================================================
-- 3.3 库存调拨 inv_（菜单：库存调拨）
-- ============================================================

CREATE TABLE inv_warehouse (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  warehouse_no VARCHAR(36)  NOT NULL                COMMENT '业务键',
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id    VARCHAR(36)      NULL                COMMENT '区域',
  name         VARCHAR(128) NOT NULL                COMMENT '仓库名称',
  address      VARCHAR(256)     NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT       NOT NULL DEFAULT 0,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_warehouse_no (warehouse_no),
  KEY idx_wh_tenant (tenant_id),
  KEY idx_wh_region (region_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库';

CREATE TABLE inv_stock (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  warehouse_no VARCHAR(36)  NOT NULL                COMMENT '所属仓库',
  item_type    VARCHAR(16)  NOT NULL                COMMENT 'CABINET/POWERBANK',
  model        VARCHAR(32)  NOT NULL DEFAULT ''     COMMENT '型号(空串=不分型号)',
  qty          INT          NOT NULL DEFAULT 0      COMMENT '结存数量',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT       NOT NULL DEFAULT 0,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock (warehouse_no, item_type, model),
  KEY idx_stock_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓/区域库存';

CREATE TABLE inv_transfer (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  transfer_no     VARCHAR(36)  NOT NULL                COMMENT '业务键 TR*',
  tenant_id       VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  from_location   VARCHAR(64)  NOT NULL                COMMENT '调出方(仓库号/站点号/点位号)',
  to_location     VARCHAR(64)  NOT NULL                COMMENT '调入方(仓库号/站点号/点位号)',
  item_type       VARCHAR(16)  NOT NULL                COMMENT 'CABINET/POWERBANK',
  powerbank_count INT          NOT NULL DEFAULT 0      COMMENT '调拨件数',
  status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/IN_TRANSIT/DONE',
  operator        VARCHAR(64)      NULL                COMMENT '经办人(employee_no)',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version         BIGINT       NOT NULL DEFAULT 0,
  deleted         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_transfer_no (transfer_no),
  KEY idx_transfer_tenant_status (tenant_id, status, created_at),
  KEY idx_transfer_from (from_location),
  KEY idx_transfer_to (to_location)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨单';

CREATE TABLE inv_transfer_item (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  transfer_no  VARCHAR(36)  NOT NULL                COMMENT '所属调拨单',
  powerbank_no VARCHAR(36)      NULL                COMMENT '充电宝(item_type=POWERBANK)',
  cabinet_no   VARCHAR(36)      NULL                COMMENT '机柜(item_type=CABINET)',
  checked      TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否已签收核对',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT       NOT NULL DEFAULT 0,
  deleted      TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_transfer_item_no (transfer_no),
  KEY idx_transfer_item_pb (powerbank_no),
  KEY idx_transfer_item_cab (cabinet_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨明细';

-- ============================================================
-- 3.4 场地 loc_ 增量（菜单：BD 拓展 CRM / 门店 Onboarding / 门店生命周期）
-- ============================================================

-- BD 拓展 CRM 商机
CREATE TABLE loc_lead (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  lead_no        VARCHAR(36)  NOT NULL                COMMENT '业务键 LD*',
  tenant_id      VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id      VARCHAR(36)      NULL,
  venue_name     VARCHAR(128) NOT NULL                COMMENT '意向场地方名称',
  contact        VARCHAR(64)      NULL                COMMENT '联系方式(掩码;明文落 pii)',
  stage          VARCHAR(16)  NOT NULL DEFAULT 'NEW'  COMMENT 'NEW/CONTACTED/NEGOTIATING/SIGNED/LOST',
  owner          VARCHAR(64)      NULL                COMMENT '负责人(employee_no)',
  expect_sites   INT          NOT NULL DEFAULT 0      COMMENT '预计可铺站点数',
  next_follow_at DATE             NULL                COMMENT '下次跟进日(仅日期)',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version        BIGINT       NOT NULL DEFAULT 0,
  deleted        TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_lead_no (lead_no),
  KEY idx_lead_tenant_stage (tenant_id, stage, updated_at),
  KEY idx_lead_owner (owner)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='BD 拓展 CRM 商机';

-- 门店自助进件
CREATE TABLE loc_venue_onboarding (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  onboarding_no  VARCHAR(36)  NOT NULL                COMMENT '业务键 OB*',
  tenant_id      VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id      VARCHAR(36)      NULL,
  venue_name     VARCHAR(128) NOT NULL                COMMENT '申请门店名称',
  contact        VARCHAR(64)      NULL                COMMENT '联系方式(掩码;明文落 pii)',
  industry       VARCHAR(32)      NULL,
  attach         JSON             NULL                COMMENT '营业执照等附件',
  requested_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '提交时刻',
  status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  review_at      DATETIME(3)      NULL                COMMENT '审核时刻(空=尚未审核)',
  review_by      VARCHAR(64)      NULL                COMMENT '审核人(employee_no)',
  review_note    VARCHAR(512)     NULL                COMMENT '审核意见/驳回原因',
  venue_no       VARCHAR(36)      NULL                COMMENT '通过后回填 loc_venue.venue_no',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version        BIGINT       NOT NULL DEFAULT 0,
  deleted        TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_onboarding_no (onboarding_no),
  KEY idx_onboarding_tenant_status (tenant_id, status, requested_at),
  KEY idx_onboarding_venue (venue_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店自助进件';

-- 门店生命周期（每站点单行，变更留痕走 loc_site_lifecycle_log）
CREATE TABLE loc_site_lifecycle (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id  VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  site_no    VARCHAR(36)  NOT NULL                COMMENT '站点',
  stage      VARCHAR(16)  NOT NULL DEFAULT 'PROSPECTING' COMMENT 'PROSPECTING/SIGNED/LIVE/ACTIVE/CHURNED/CLOSED',
  stage_at   DATE             NULL                COMMENT '进入当前阶段日期(仅日期)',
  owner      VARCHAR(64)      NULL                COMMENT '负责人(employee_no)',
  gmv_ltm    DECIMAL(18,2) NOT NULL DEFAULT 0     COMMENT '近 12 月 GMV',
  currency   VARCHAR(8)   NOT NULL DEFAULT 'AED',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT       NOT NULL DEFAULT 0,
  deleted    TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_site_lifecycle_site (site_no),
  KEY idx_site_lifecycle_stage (tenant_id, stage)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店生命周期';

-- 阶段流转留痕（append，无 version/deleted）
CREATE TABLE loc_site_lifecycle_log (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id  VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  site_no    VARCHAR(36)  NOT NULL,
  from_stage VARCHAR(16)      NULL                COMMENT 'PROSPECTING/SIGNED/LIVE/ACTIVE/CHURNED/CLOSED(空=首次)',
  to_stage   VARCHAR(16)  NOT NULL                COMMENT 'PROSPECTING/SIGNED/LIVE/ACTIVE/CHURNED/CLOSED',
  operator   VARCHAR(64)      NULL                COMMENT '操作人(employee_no)',
  reason     VARCHAR(512)     NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_site_lc_log_site (site_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店生命周期流转留痕(append)';

-- ============================================================
-- 3.5 代理商 agt_ 增量（ADR-012，菜单：代理商管理）
-- 注：agt_account 已建于 pb_core-loc-agt-iam.sql，本文件不重建。
--     现存列 = account_no/tenant_id/agent_no/username/cred_ref/status；
--     db-design v2 另需 agent_name(冗余展示名)/login_phone(登录手机,掩码)；
--     dataScope 按 §1.7 复用 iam_data_scope(subject_type=AGENT_ACCOUNT, subject_no=account_no)，不落本表列。
--     缺列补法（待放行，本脚本不执行）：
--     ALTER TABLE agt_account
--       ADD COLUMN agent_name  VARCHAR(128) NULL COMMENT '冗余展示名(写入时快照,不回溯)' AFTER agent_no,
--       ADD COLUMN login_phone VARCHAR(32)  NULL COMMENT '登录手机(掩码;明文落 pii)'     AFTER username;
-- ============================================================

-- 设备/点位划拨记录（append，无 version/deleted）
CREATE TABLE agt_assignment (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  assign_no   VARCHAR(36)  NOT NULL                COMMENT '业务键',
  tenant_id   VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  agent_no    VARCHAR(36)  NOT NULL                COMMENT '代理商',
  target_type VARCHAR(16)  NOT NULL                COMMENT 'CABINET/LOCATION/SITE',
  target_no   VARCHAR(36)  NOT NULL                COMMENT '被划拨对象业务键',
  action      VARCHAR(16)  NOT NULL                COMMENT 'ASSIGN/REVOKE',
  operator    VARCHAR(64)      NULL                COMMENT '操作人(employee_no)',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_assign_no (assign_no),
  KEY idx_assign_agent_time (agent_no, created_at),
  KEY idx_assign_target (target_type, target_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理设备/点位划拨记录(append)';

-- 代理分润配置
CREATE TABLE agt_commission (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  rule_no       VARCHAR(36)  NOT NULL                COMMENT '业务键 AC*',
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  agent_no      VARCHAR(36)  NOT NULL                COMMENT '代理商',
  agent_name    VARCHAR(128)     NULL                COMMENT '冗余展示名(写入时快照,不回溯)',
  dimension     VARCHAR(16)  NOT NULL DEFAULT 'GMV'  COMMENT 'GMV/ORDER_COUNT',
  rate          DECIMAL(5,4) NOT NULL DEFAULT 0      COMMENT '分润比例 0..1',
  fixed_amount  DECIMAL(18,2) NOT NULL DEFAULT 0     COMMENT 'dimension=ORDER_COUNT 时的单均金额',
  currency      VARCHAR(8)   NOT NULL DEFAULT 'AED',
  mode          VARCHAR(16)  NOT NULL DEFAULT 'LEDGER' COMMENT 'CHANNEL_SPLIT/LEDGER',
  effective_at  DATE             NULL                COMMENT '生效日(仅日期)',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agt_commission_no (rule_no),
  KEY idx_agt_commission_agent (tenant_id, agent_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理分润配置';

-- 代理辖域（拆 Agent.regionScope CSV，db-design §1.7）
CREATE TABLE agt_agent_region (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id  VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  agent_no   VARCHAR(36)  NOT NULL                COMMENT '代理商',
  region_id  VARCHAR(36)  NOT NULL                COMMENT '辖区(→md_region.region_id)',
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT       NOT NULL DEFAULT 0,
  deleted    TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_region (agent_no, region_id),
  KEY idx_agent_region_region (region_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理辖域(多值拆表)';

-- ============================================================
-- 3.6 工单 wo_ 增量（菜单：SLA 管理）
-- 注：wo_sla（逐单计时）已建于 pb_core-user-ad-workorder.sql，此处只补规则表
-- ============================================================
CREATE TABLE wo_sla_rule (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  sla_no        VARCHAR(36)  NOT NULL                COMMENT '业务键 SLA*',
  tenant_id     VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  wo_type       VARCHAR(16)  NOT NULL                COMMENT 'FAULT/REFILL/INSPECT/INSTALL/REMOVE/COMPLAINT/CLEAN',
  response_mins INT          NOT NULL DEFAULT 0      COMMENT '响应时限(分钟)',
  resolve_mins  INT          NOT NULL DEFAULT 0      COMMENT '解决时限(分钟)',
  escalate_to   VARCHAR(64)      NULL                COMMENT '超时升级到(role_no/employee_no)',
  active        TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version       BIGINT       NOT NULL DEFAULT 0,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sla_rule_no (sla_no),
  UNIQUE KEY uk_sla_rule_type (tenant_id, wo_type),
  KEY idx_sla_rule_active (tenant_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLA 规则(按工单类型)';

-- ============================================================
-- 附：dev_alert → dev_alarm 数据迁移草稿（**未执行，人工复核后再放开**）
-- 说明：v1 dev_alert 列 alert_no/severity/code/message 分别对应 v2 alarm_no/level/alarm_code/remark；
--       v1 status 枚举 OPEN/ACK/RESOLVED → v2 OPEN/ACKED/CLOSED，需值映射；
--       v1 无 site_no/agent_no/vendor_code/vendor_error_code/wo_no/occurred_at，
--       site_no/agent_no 应回填自 dev_cabinet（数据范围过滤依赖它们，不回填则代理看不到历史告警）。
-- ============================================================
-- INSERT INTO dev_alarm
--   (alarm_no, tenant_id, cabinet_no, site_no, agent_no, vendor_code,
--    alarm_code, vendor_error_code, level, source, occurred_at, status,
--    wo_no, remark, dedup_key, count, created_at, updated_at)
-- SELECT
--   a.alert_no,
--   a.tenant_id,
--   a.cabinet_no,
--   c.site_no,                       -- 回填：v1 无此列
--   c.agent_no,                      -- 回填：v1 无此列
--   c.vendor_code,                   -- 回填：v1 无此列
--   COALESCE(a.code, 'UNKNOWN'),     -- v1 code 可空，v2 alarm_code NOT NULL
--   NULL,                            -- vendor_error_code：v1 未留厂商原始码，历史不可考
--   a.severity,
--   a.source,
--   a.created_at,                    -- occurred_at：v1 无独立发生时刻，用 created_at 近似
--   CASE a.status WHEN 'ACK' THEN 'ACKED' WHEN 'RESOLVED' THEN 'CLOSED' ELSE 'OPEN' END,
--   NULL,                            -- wo_no：v1 告警未与工单关联
--   a.message,                       -- v1 message 落 v2 remark（v2 描述文案来自 dev_alarm_code 字典）
--   a.dedup_key,
--   a.count,
--   a.created_at,
--   a.updated_at
-- FROM dev_alert a
-- LEFT JOIN dev_cabinet c ON c.cabinet_no = a.cabinet_no;
--
-- 迁移前：把 v1 出现过的 code 全量补进 dev_alarm_code 字典，否则 alarm_code 成孤儿引用：
-- INSERT INTO dev_alarm_code (code, message, level)
-- SELECT DISTINCT COALESCE(a.code,'UNKNOWN'), COALESCE(a.message,'(legacy)'), a.severity
-- FROM dev_alert a
-- WHERE NOT EXISTS (SELECT 1 FROM dev_alarm_code d WHERE d.code = COALESCE(a.code,'UNKNOWN'));
--
-- 校验通过（行数一致 + 抽样比对）后，再由 DBA 手工执行下线，本脚本不执行：
-- RENAME TABLE dev_alert TO dev_alert_bak_v1;   -- 建议先改名观察一个归档周期
-- DROP TABLE dev_alert;                          -- ⚠️ 确认无回滚需求后才执行
