-- 通知重发的幂等与溯源（前端契约 NotifyLog / NotifyResendPayload）
--
-- **为什么重发必须幂等**：重发一条短信 = 真的多发一次、多扣一次钱。
-- 运营双击提交或网络重试各落一笔，用户就收到两条。故服务端按幂等键拒绝第二次。
--
-- **为什么重发是新增一条而不是改原记录**：审计要看得见「发了两次」。
-- 原记录一字不改，新记录用 resend_of 指回去。

SET NAMES utf8mb4;

ALTER TABLE notify_log
  ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64) NULL COMMENT '幂等键；重发/试发必带，历史 seed 为 null',
  ADD COLUMN IF NOT EXISTS resend_of       VARCHAR(36) NULL COMMENT '由哪条记录重发而来；null=原始发送';

-- 幂等键唯一 —— **这是拒绝重复发送的执行手段**，靠应用层判重在并发下会漏。
-- 允许多个 NULL（历史数据），MySQL 唯一索引对 NULL 不去重，正合需要。
ALTER TABLE notify_log
  ADD UNIQUE KEY IF NOT EXISTS uk_notify_idem (tenant_id, idempotency_key);

ALTER TABLE notify_log
  ADD KEY IF NOT EXISTS idx_notify_resend_of (resend_of);
