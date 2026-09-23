-- ============================================================
-- powerbank · 分润明细的幂等键（M1「分润生成链路」）
--
-- 背景：订单结算后由 OrderSettledEvent 生成 share_record。事件经 outbox 投递，
--       **重投是设计内的**（进程崩溃、MQ 重试）。没有唯一键的话，重投一次就把同一单的
--       分成记第二遍 —— 而这张表是结算单的上游，钱会被真的多算一次。
--
-- 约束选 (order_no, dimension, payee_no)：一单里同一个分成方在同一维度只能有一条。
-- 不含 period —— period 由结算时刻定格，若把它放进键里，同一单被补算两次（跨月重跑）
-- 会绕过约束再记一笔，正是要防的那件事。
--
-- 注意：本脚本用了 MariaDB 专有的 `ADD ... IF NOT EXISTS`（与 V8 起的既有写法一致，
-- 本库锁定 MariaDB 12.x）。
-- ============================================================
SET NAMES utf8mb4;

-- dimension 列是 V34 之后的写法；老库若只有 payee_type，先补齐再建约束。
ALTER TABLE share_record
  ADD COLUMN IF NOT EXISTS dimension VARCHAR(16) NULL COMMENT 'VENUE/AGENT，与 share_rule 同枚举';

-- 存量行（演示数据）用 payee_type 回填，否则唯一键会把它们全判成 (null,null) 冲突。
UPDATE share_record SET dimension = payee_type WHERE dimension IS NULL;

ALTER TABLE share_record
  ADD UNIQUE KEY IF NOT EXISTS uk_srec_order_payee (order_no, dimension, payee_no);

-- 追溯用：这笔分成的比例是从哪份合同/规则来的。排错时「为什么是 15%」要能当场回答。
ALTER TABLE share_record
  ADD COLUMN IF NOT EXISTS source_no VARCHAR(36) NULL COMMENT '费率来源：合同号或规则号';
