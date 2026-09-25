SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 机柜状态机 / 上线门禁 / 保护动作 / 试借还 / 信号字典（TDD-运营核心流程/04）
--
-- 保护动作是设备层的止损（卡宝禁仓、电池锁仓、整柜停借），由信号立即触发，不等业务告警。
-- 用「持有者 + 引用计数」表达：同一仓位被两个原因禁用时，只解除其中一个，仓位仍禁用。
--   · slot_index = -1 表示整柜（不用 NULL：NULL 不参与唯一键，整柜动作会失去去重）；
--   · active = 1 生效中，NULL = 已释放（NULL 不参与唯一键，允许同键多条历史；用 0 会第二次释放就撞键）。
-- ============================================================
ALTER TABLE dev_cabinet
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'IN_STOCK'
    COMMENT 'IN_STOCK 在库 / IN_TRANSIT 运输中 / DEPLOYED 已布放 / FAULT 故障 / RETIRED 已退役',
  ADD COLUMN IF NOT EXISTS went_live_at    DATETIME(3)  NULL COMMENT '最近一次上线时刻',
  ADD COLUMN IF NOT EXISTS trial_passed_at DATETIME(3)  NULL COMMENT '最近一次试借还通过时刻（换点位后需重测）',
  ADD COLUMN IF NOT EXISTS bound_at        DATETIME(3)  NULL COMMENT '最近一次绑定点位时刻',
  ADD COLUMN IF NOT EXISTS fault_reason    VARCHAR(512) NULL,
  ADD COLUMN IF NOT EXISTS retired_at      DATETIME(3)  NULL;

CREATE TABLE IF NOT EXISTS dev_protection (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  protection_no   VARCHAR(36)  NOT NULL COMMENT '业务键 PRT*',
  tenant_id       VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  cabinet_no      VARCHAR(36)  NOT NULL,
  slot_index      INT          NOT NULL DEFAULT -1 COMMENT '-1 = 整柜',
  action          VARCHAR(16)  NOT NULL COMMENT 'STOP_RENT 整柜停借 / SLOT_DISABLE 仓位禁用 / SLOT_LOCK 仓位锁定 / DERATE 降功率',
  holder_type     VARCHAR(16)  NOT NULL COMMENT 'SIGNAL 设备信号 / ALARM 业务告警 / MANUAL 人工',
  holder_ref      VARCHAR(64)  NOT NULL COMMENT '信号码:发生键 / 告警号 / 操作人',
  reason          VARCHAR(256) NOT NULL,
  active          TINYINT(1)       NULL DEFAULT 1 COMMENT '1 生效中；NULL 已释放',
  released_at     DATETIME(3)      NULL,
  release_reason  VARCHAR(256)     NULL,
  site_no         VARCHAR(36)      NULL COMMENT '冗余·数据范围',
  agent_no        VARCHAR(36)      NULL COMMENT '冗余·数据范围',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by      VARCHAR(36)      NULL,
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(36)      NULL,
  version         BIGINT       NOT NULL DEFAULT 0,
  deleted         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_protection_no (protection_no),
  UNIQUE KEY uk_protection_holder (cabinet_no, slot_index, action, holder_type, holder_ref, active),
  KEY idx_protection_active (cabinet_no, active),
  KEY idx_protection_scope (tenant_id, agent_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备保护动作（引用计数）';

CREATE TABLE IF NOT EXISTS dev_trial_rent (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  trial_no         VARCHAR(36)  NOT NULL COMMENT '业务键 TRL*',
  tenant_id        VARCHAR(36)  NOT NULL DEFAULT 'MAIN',
  cabinet_no       VARCHAR(36)  NOT NULL,
  slot_index       INT              NULL,
  powerbank_no     VARCHAR(36)      NULL,
  status           VARCHAR(16)  NOT NULL DEFAULT 'EJECTING' COMMENT 'EJECTING 弹出中 / WAIT_RETURN 待归还 / PASSED 通过 / FAILED 失败 / EXPIRED 超时',
  eject_command_no VARCHAR(64)      NULL,
  ejected_at       DATETIME(3)      NULL,
  returned_at      DATETIME(3)      NULL,
  fail_reason      VARCHAR(256)     NULL,
  operator         VARCHAR(36)  NOT NULL,
  site_no          VARCHAR(36)      NULL,
  agent_no         VARCHAR(36)      NULL,
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by       VARCHAR(36)      NULL,
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by       VARCHAR(36)      NULL,
  version          BIGINT       NOT NULL DEFAULT 0,
  deleted          TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_trial_no (trial_no),
  KEY idx_trial_cabinet (cabinet_no, id),
  KEY idx_trial_scope (tenant_id, agent_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试借还记录';

CREATE TABLE IF NOT EXISTS dev_event_code (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code              VARCHAR(32)  NOT NULL,
  name              VARCHAR(64)  NOT NULL,
  name_en           VARCHAR(64)      NULL,
  name_ar           VARCHAR(64)      NULL,
  category          VARCHAR(16)  NOT NULL COMMENT 'LINK 连接 / SLOT 仓位 / BATTERY 电池 / THERMAL 温控 / TXN 交易 / MAINT 维护 / TAMPER 拆机',
  scope             VARCHAR(16)  NOT NULL COMMENT 'CABINET 整柜 / SLOT 仓位 / POWERBANK 充电宝',
  protective_action VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE 无 / STOP_RENT 整柜停借 / SLOT_DISABLE 仓位禁用 / SLOT_LOCK 仓位锁定 / DERATE 降功率 / AUTO_EJECT_RETRY 自动重弹一次',
  clears_code       VARCHAR(32)      NULL COMMENT '本信号出现即解除哪个信号的保护',
  feeds             VARCHAR(256)     NULL COMMENT '喂给的业务告警码（说明用）',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version           BIGINT       NOT NULL DEFAULT 0,
  deleted           TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备信号字典（全局）';

INSERT INTO dev_event_code (code, name, name_en, category, scope, protective_action, clears_code, feeds) VALUES
  ('HEARTBEAT',         '心跳',           'Heartbeat',              'LINK',    'CABINET',   'NONE',             NULL,             '可借 / 可还（在线口径）'),
  ('SLOT_STUCK',        '仓位卡宝',       'Slot jammed',            'SLOT',    'SLOT',      'AUTO_EJECT_RETRY', NULL,             '可借 / 可还'),
  ('SLOT_EJECT_OK',     '仓位弹出成功',   'Slot ejected',           'SLOT',    'SLOT',      'NONE',             'SLOT_STUCK',     '—'),
  ('LOCK_FAIL',         '锁扣异常',       'Lock failure',           'SLOT',    'SLOT',      'SLOT_DISABLE',     NULL,             '可借 / 可还'),
  ('LOCK_OK',           '锁扣自检通过',   'Lock self-test OK',      'SLOT',    'SLOT',      'NONE',             'LOCK_FAIL',      '—'),
  ('SLOT_CHARGE_FAIL',  '仓位不充电',     'Slot not charging',      'SLOT',    'SLOT',      'SLOT_DISABLE',     NULL,             '可借'),
  ('SLOT_CHARGE_OK',    '仓位充电恢复',   'Slot charging OK',       'SLOT',    'SLOT',      'NONE',             'SLOT_CHARGE_FAIL','—'),
  ('TEMP_HIGH',         '机内温度高',     'Over temperature',       'THERMAL', 'CABINET',   'DERATE',           NULL,             'CABINET_OVERHEAT'),
  ('TEMP_OK',           '温度恢复',       'Temperature normal',     'THERMAL', 'CABINET',   'NONE',             'TEMP_HIGH',      '—'),
  ('BATTERY_ABNORMAL',  '宝电池异常',     'Battery abnormal',       'BATTERY', 'SLOT',      'SLOT_LOCK',        NULL,             'BATTERY_HAZARD'),
  ('BATTERY_UNHEALTHY', '宝健康衰减',     'Battery degraded',       'BATTERY', 'POWERBANK', 'NONE',             NULL,             '资产（待报废）'),
  ('EJECT_TIMEOUT',     '单次弹出失败',   'Eject timeout',          'TXN',     'SLOT',      'NONE',             NULL,             'RENT_NOT_DELIVERED · EJECT_FAIL_RATE'),
  ('COMMAND_RESULT',    '指令回执',       'Command result',         'TXN',     'CABINET',   'NONE',             NULL,             '试借还 · 远程指令'),
  ('RETURN_SN_SEEN',    '仓位识别到宝',   'Powerbank detected',     'TXN',     'SLOT',      'NONE',             NULL,             'RETURN_NOT_RECOGNIZED · 试借还'),
  ('SIGNAL_WEAK',       '通信信号弱',     'Weak signal',            'LINK',    'CABINET',   'NONE',             NULL,             '巡检'),
  ('SCREEN_FAULT',      '屏幕异常',       'Screen fault',           'MAINT',   'CABINET',   'NONE',             NULL,             '巡检'),
  ('POWER_ABNORMAL',    '供电异常',       'Power abnormal',         'LINK',    'CABINET',   'NONE',             NULL,             '可借 / 可还（离线根因）'),
  ('FW_UPGRADE_FAIL',   '固件升级失败',   'Firmware upgrade failed','MAINT',   'CABINET',   'NONE',             NULL,             'OTA'),
  ('TAMPER',            '拆机 / 位移',    'Tamper',                 'TAMPER',  'CABINET',   'NONE',             NULL,             'CABINET_RELOCATED')
ON DUPLICATE KEY UPDATE name = VALUES(name), category = VALUES(category), scope = VALUES(scope),
  protective_action = VALUES(protective_action), clears_code = VALUES(clears_code), feeds = VALUES(feeds);
