-- ============================================================
-- V198 对接独立进销存（docs/technical/独立进销存/D §2.3，S3）
--   inventory_sync_state：键值 —— 仓库映射（wh:{本地仓号} → 库存服务 locationId）、物料映射（item:{CABINET|POWERBANK} → itemId）、
--                         领取单号（claim:{设备号} → claimNo）、事件游标（events:seq）
--   inventory_inbox      ：拉到的库存事件，event_id 唯一 —— 至少一次投递下重复事件只处理一次
-- 引擎开关：sys_param inv.engine = LOCAL（默认，本地设备表）/ SERVICE（独立进销存）
-- ============================================================
CREATE TABLE IF NOT EXISTS inventory_sync_state (
  k           VARCHAR(96)  NOT NULL,
  v           VARCHAR(255) NOT NULL,
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (k)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='独立进销存对接的映射与游标';

CREATE TABLE IF NOT EXISTS inventory_inbox (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id          VARCHAR(64)  NOT NULL,
  seq               BIGINT       NOT NULL,
  type              VARCHAR(48)  NOT NULL COMMENT '库存服务事件类型（InboundPosted、DispatchClaimed 等，原样保存）',
  resource_id       VARCHAR(64)      NULL,
  resource_version  INT              NULL,
  payload           TEXT             NULL,
  status            VARCHAR(16)  NOT NULL COMMENT 'DONE 已处理 / FAILED 处理失败',
  error             VARCHAR(512)     NULL,
  processed_at      DATETIME(3)      NULL,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_inbox_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存事件收件箱（去重与重试）';

INSERT INTO sys_param (tenant_id, param_key, label, value, group_name, value_type, value_options, nullable, hint)
SELECT 'MAIN', 'inv.engine', '库存引擎：LOCAL 本地设备表 / SERVICE 独立进销存', 'LOCAL', '库存', 'ENUM',
       '[{"value": "LOCAL", "label": "本地设备表"}, {"value": "SERVICE", "label": "独立进销存"}]', 0,
       'S3：切到 SERVICE 前须配好 sharehub.inventory.base-url / app-key / app-secret 并完成主数据与存量迁移'
WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'inv.engine');
