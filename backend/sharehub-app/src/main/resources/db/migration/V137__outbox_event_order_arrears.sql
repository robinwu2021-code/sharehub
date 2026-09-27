SET NAMES utf8mb4;
-- ════════════════════════════════════════════════════════════════════
-- sys_outbox.event_type 词表加 ORDER_ARREARS（订单欠费）
--
-- 欠费站内信由 trade 发 OrderArrearsEvent、user 监听后发信（534ea550）——
-- 不能让 trade 直接调 user 的服务，否则 trade ↔ user 成环（ArchitectureTest#noCyclesBetweenDomains）。
-- 事件经 outbox 落库，而这一列的注释就是它的取值词表（V84 起），
-- StoredValueInVocabularyTest 会拦下「库里有、列注释没声明」的值。
-- ════════════════════════════════════════════════════════════════════

ALTER TABLE sys_outbox
  MODIFY COLUMN event_type VARCHAR(64) NOT NULL
  COMMENT 'ASSET_ASSIGNED/CONTRACT_SIGNED/ORDER_SETTLED/CABINET_WENT_LIVE/DEVICE_SIGNAL/WORK_ORDER_COMPLETED/SITE_CLOSED/AGENT_STATUS_CHANGED/ORDER_ARREARS';
