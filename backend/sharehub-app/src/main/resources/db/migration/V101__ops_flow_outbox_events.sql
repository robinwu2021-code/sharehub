SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · Outbox 事件词表补全（TDD-运营核心流程/07 §一）
--
-- V84 把 sys_outbox.event_type 的注释从「举例」改成了闭合词表，并约定
-- 「以后新增事件要连这条注释一起改」。本批新增三个事件：
--   · CABINET_WENT_LIVE      机柜通过上线门禁（站点据此从筹备转营业）
--   · DEVICE_SIGNAL          归一化设备信号（业务告警、试借还订阅；心跳不入 Outbox）
--   · WORK_ORDER_COMPLETED   工单完工（业务告警据此复核自动验收 / 返工）
-- ============================================================
ALTER TABLE sys_outbox
  MODIFY COLUMN event_type VARCHAR(64) NOT NULL
  COMMENT 'ASSET_ASSIGNED/CONTRACT_SIGNED/ORDER_SETTLED/CABINET_WENT_LIVE/DEVICE_SIGNAL/WORK_ORDER_COMPLETED';
