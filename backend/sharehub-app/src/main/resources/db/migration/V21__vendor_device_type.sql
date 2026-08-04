-- 供应商 × 设备类型（ADR-018 §五 · 设计v3-数据库变更 §五）
--
-- 一家供应商可能同时供柜机与充电桩，故是多值关系。
-- **不在 gw_vendor 上加多值列** —— db-design §1.7 明确多值拆表。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS gw_vendor_device_type (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  vendor_code    VARCHAR(32) NOT NULL,
  device_type    VARCHAR(32) NOT NULL COMMENT 'POWERBANK/EV_PILE/LOCKER',
  protocol       VARCHAR(32) NOT NULL COMMENT 'VENDOR_CLOUD(A厂商云)/DIRECT(B直连)/OCPP16J/OCPP201(C标准)',
  command_family VARCHAR(32) NOT NULL COMMENT '指令族',
  status         VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by     VARCHAR(36) NULL,
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by     VARCHAR(36) NULL,
  version        BIGINT      NOT NULL DEFAULT 0,
  deleted        TINYINT(1)  NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  -- 一家供应商的同一设备类型只能有一种协议：两种协议并存无法路由
  UNIQUE KEY uk_vendor_device (vendor_code, device_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商支持的设备类型（多值拆表）';

-- 驱动声明的能力白名单。**这是「供应商只做设备管理」红线的执行手段**：
-- 厂商云的订单/支付类能力不在白名单里，driver 层面就不存在。
ALTER TABLE gw_vendor_config
  ADD COLUMN IF NOT EXISTS capability_manifest JSON NULL COMMENT 'driver 声明的 commands/events 白名单';

INSERT INTO gw_vendor_device_type (vendor_code, device_type, protocol, command_family)
VALUES ('DEMO','POWERBANK','VENDOR_CLOUD','POWERBANK')
ON DUPLICATE KEY UPDATE vendor_code = vendor_code;
