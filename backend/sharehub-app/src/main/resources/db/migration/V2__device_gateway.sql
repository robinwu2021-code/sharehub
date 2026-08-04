-- 自动生成，勿手改：源文件 docs/technical/ddl/pb_core-device-gateway.sql
-- 重新同步：python3 backend/scripts/sync-migrations.py
-- 已做的转换：剥离 USE/CREATE DATABASE · 幂等化(IF [NOT] EXISTS) · 剥离 AFTER 子句 ·
--            保留字列名加反引号

-- ============================================================
-- powerbank · pb_core 建表：设备 dev_ · 接入网关 gw_
-- 对齐 db-design.md §3 / TDD-access-gateway / 系统领域模型 D3,D5
-- MySQL 8 · InnoDB · utf8mb4_0900_ai_ci
-- BaseEntity: id/region_id/created_at/updated_at/version/deleted；tenant_id 默认 MAIN(域列)
-- ============================================================
SET NAMES utf8mb4;

-- ---------- D3 设备 dev_ ----------
CREATE TABLE IF NOT EXISTS dev_cabinet (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  cabinet_no       VARCHAR(36)  NOT NULL COMMENT '机柜业务键',
  tenant_id        VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  region_id        VARCHAR(36)      NULL,
  agent_no         VARCHAR(36)      NULL COMMENT '归属代理(冗余,随站点)',
  location_no      VARCHAR(36)      NULL COMMENT '归属点位',
  sn               VARCHAR(64)  NOT NULL COMMENT '设备序列号(全局唯一)',
  vendor_code      VARCHAR(32)  NOT NULL COMMENT '供应商',
  model            VARCHAR(32)      NULL,
  slot_total       INT          NOT NULL DEFAULT 0,
  online_status    VARCHAR(16)  NOT NULL DEFAULT 'OFFLINE' COMMENT 'ONLINE/OFFLINE',
  last_heartbeat_at DATETIME(3)     NULL,
  fw_version       VARCHAR(32)      NULL,
  status           VARCHAR(16)  NOT NULL DEFAULT 'DEPLOYED' COMMENT 'DEPLOYED/FAULT/RETIRED',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version          BIGINT       NOT NULL DEFAULT 0,
  deleted          TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cabinet_no (cabinet_no),
  UNIQUE KEY uk_cabinet_sn (sn),
  KEY idx_cab_tenant (tenant_id),
  KEY idx_cab_location (location_no),
  KEY idx_cab_agent (agent_no),
  KEY idx_cab_vendor (vendor_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机柜/充电桩';

CREATE TABLE IF NOT EXISTS dev_slot (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id    VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  cabinet_no   VARCHAR(36) NOT NULL,
  slot_index   INT         NOT NULL,
  powerbank_no VARCHAR(36)     NULL COMMENT '在仓充电宝',
  lock_status  VARCHAR(16) NOT NULL DEFAULT 'LOCKED' COMMENT 'LOCKED/UNLOCKED',
  health       VARCHAR(16) NOT NULL DEFAULT 'OK'     COMMENT 'OK/FAULT',
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT      NOT NULL DEFAULT 0,
  deleted      TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_slot (cabinet_no, slot_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓位';

CREATE TABLE IF NOT EXISTS dev_powerbank (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  powerbank_no VARCHAR(36) NOT NULL,
  tenant_id    VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  sn           VARCHAR(64) NOT NULL,
  vendor_code  VARCHAR(32)     NULL,
  battery      INT             NULL,
  status       VARCHAR(16) NOT NULL DEFAULT 'IN_STOCK' COMMENT 'IN_STOCK/DEPLOYED/IN_USE/RETURNED/SCRAP/LOST',
  cabinet_no   VARCHAR(36)     NULL COMMENT '当前所在机柜',
  slot_index   INT             NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT      NOT NULL DEFAULT 0,
  deleted      TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_powerbank_no (powerbank_no),
  UNIQUE KEY uk_powerbank_sn (sn),
  KEY idx_pb_cabinet (cabinet_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充电宝';

CREATE TABLE IF NOT EXISTS dev_shadow (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  cabinet_no   VARCHAR(36) NOT NULL,
  slots        JSON            NULL COMMENT '仓位快照',
  online       TINYINT(1)  NOT NULL DEFAULT 0,
  snapshot_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_shadow_cabinet (cabinet_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备影子(主 Redis,此为落库快照)';

CREATE TABLE IF NOT EXISTS dev_heartbeat (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  cabinet_no VARCHAR(36) NOT NULL,
  metrics    JSON            NULL COMMENT 'fw/信号/温度/电量',
  beat_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_hb_cab_time (cabinet_no, beat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='心跳遥测(append,按 beat_at 月分区)';

CREATE TABLE IF NOT EXISTS dev_ota_release (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  release_no   VARCHAR(36) NOT NULL,
  tenant_id    VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  fw_type      VARCHAR(32) NOT NULL,
  vendor_code  VARCHAR(32)     NULL COMMENT '供应商(逻辑引用 gw_vendor)',
  fw_version   VARCHAR(32) NOT NULL COMMENT '固件版本号(如 1.4.2)。不能叫 version —— 那是乐观锁列名',
  version_code INT         NOT NULL,
  artifact_url VARCHAR(512)    NULL,
  checksum     VARCHAR(128)    NULL,
  mandatory    TINYINT(1)  NOT NULL DEFAULT 0,
  status       VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/PAUSED/COMPLETED/ARCHIVED',
  release_notes TEXT           NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version      BIGINT      NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted      TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ota_release_no (release_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OTA 版本';

CREATE TABLE IF NOT EXISTS dev_ota_rollout (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  rollout_no  VARCHAR(36) NOT NULL,
  release_no  VARCHAR(36) NOT NULL,
  scope       VARCHAR(16) NOT NULL COMMENT 'DEVICE/SITE/ALL',
  target_ref  VARCHAR(36)     NULL,
  forced      TINYINT(1)  NOT NULL DEFAULT 0,
  status      VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/PAUSED/COMPLETED/CANCELED',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rollout_no (rollout_no),
  KEY idx_rollout_release (release_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OTA 投放';

CREATE TABLE IF NOT EXISTS dev_ota_task (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_no          VARCHAR(36) NOT NULL,
  rollout_no       VARCHAR(36) NOT NULL,
  cabinet_no       VARCHAR(36) NOT NULL,
  status           VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DOWNLOADING/DOWNLOADED/INSTALLING/SUCCESS/FAILED/ROLLED_BACK',
  progress         INT         NOT NULL DEFAULT 0,
  previous_version VARCHAR(32)     NULL,
  error            VARCHAR(256)    NULL,
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ota_task_no (task_no),
  KEY idx_task_rollout (rollout_no),
  KEY idx_task_cabinet (cabinet_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OTA 逐设备任务';

CREATE TABLE IF NOT EXISTS dev_alert (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  alert_no   VARCHAR(36) NOT NULL,
  tenant_id  VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  cabinet_no VARCHAR(36)     NULL,
  source     VARCHAR(16) NOT NULL COMMENT 'DEVICE/OTA/RENT',
  severity   VARCHAR(16) NOT NULL DEFAULT 'WARN' COMMENT 'INFO/WARN/CRITICAL',
  code       VARCHAR(32)     NULL,
  message    VARCHAR(256)    NULL,
  status     VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ACK/RESOLVED',
  dedup_key  VARCHAR(128)    NULL,
  count      INT         NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_alert_no (alert_no),
  KEY idx_alert_dedup (dedup_key),
  KEY idx_alert_cabinet (cabinet_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备告警';

-- ---------- D5 接入网关 gw_（access-gateway 进程；全局表 gw_vendor 不注入租户）----------
CREATE TABLE IF NOT EXISTS gw_vendor (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  vendor_code VARCHAR(32)  NOT NULL,
  name        VARCHAR(128) NOT NULL,
  access_mode VARCHAR(16)  NOT NULL COMMENT 'TCP/MQTT/HTTP_API',
  api_base    VARCHAR(256)     NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version     BIGINT       NOT NULL DEFAULT 0,
  deleted     TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_vendor_code (vendor_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='硬件供应商';

CREATE TABLE IF NOT EXISTS gw_vendor_config (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  vendor_code  VARCHAR(32)  NOT NULL,
  tenant_id    VARCHAR(36)  NOT NULL DEFAULT 'MAIN' COMMENT '租户级差异',
  secret       VARCHAR(512)     NULL COMMENT '[KMS]',
  callback_url VARCHAR(256)     NULL,
  sign_type    VARCHAR(32)      NULL,
  params       JSON             NULL COMMENT 'driver 参数',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_vendor_cfg (vendor_code, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商接入配置';

CREATE TABLE IF NOT EXISTS gw_device_binding (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  sn          VARCHAR(64) NOT NULL,
  cabinet_no  VARCHAR(36)     NULL,
  instance_id VARCHAR(64)     NULL COMMENT '网关实例(会话路由)',
  session_at  DATETIME(3)     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_binding_sn (sn),
  KEY idx_binding_instance (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备会话绑定';

CREATE TABLE IF NOT EXISTS gw_command_log (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  command_id VARCHAR(36) NOT NULL COMMENT '幂等键',
  tenant_id  VARCHAR(36) NOT NULL DEFAULT 'MAIN',
  cabinet_no VARCHAR(36) NOT NULL,
  type       VARCHAR(24) NOT NULL COMMENT 'EJECT_ANY/EJECT_SLOT/LOCK/...',
  params     JSON            NULL,
  order_no   VARCHAR(36)     NULL,
  status     VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/ACKED/CONFIRMED/TIMEOUT/FAILED',
  retries    INT         NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_command_id (command_id),
  KEY idx_cmd_cabinet (cabinet_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指令日志(幂等)';

CREATE TABLE IF NOT EXISTS gw_message_log (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  sn         VARCHAR(64) NOT NULL,
  direction  VARCHAR(8)  NOT NULL COMMENT 'UP/DOWN',
  vendor_code VARCHAR(32)    NULL,
  raw        VARBINARY(2048) NULL COMMENT '原始报文(脱敏)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_msg_sn_time (sn, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上下行报文留痕(append,月分区,短 TTL)';
