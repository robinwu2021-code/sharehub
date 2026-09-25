SET NAMES utf8mb4;
-- ============================================================
-- 运营核心流程 · 词表补全（TDD-运营核心流程/05 · 06）
--
--   · cs_ticket.channel：业务告警的兜底处置会开客服单（RENT_NOT_DELIVERED 自愈失败 → 主动联系用户），
--     渠道记 ALARM；MANUAL 是运营手工建单一直在写、列注释漏了的值，一并补上。
--   · wo_handle.fault_reason_code：V99 写的是「同 wo_order…」，词表卡口认不出引用，展开成与 wo_order 同一份词表。
-- ============================================================
ALTER TABLE cs_ticket
  MODIFY COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'APP' COMMENT 'APP/MP/H5/PHONE/EMAIL/MANUAL/ALARM';

ALTER TABLE wo_handle
  MODIFY COLUMN fault_reason_code VARCHAR(32) NULL
    COMMENT 'NETWORK 网络 / POWER 供电 / SLOT_MECH 仓位机械 / LOCK 锁 / BATTERY 电池 / SCREEN 屏 / DAMAGE 人为损坏 / OTHER 其他';
