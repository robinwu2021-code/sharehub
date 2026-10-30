-- ============================================================
-- V179 请款幂等：pay_order 落幂等键并加唯一约束（缺陷 D2 / D3，2026-10-29）
--
-- PaymentService.pay() 早就收 idempotencyKey、也传给了支付通道，但**自己不存、不判重**：
--   · D2：逾期买断与用户归还并发时，两边都走到请款，同一单两笔 pay_order；
--   · D3：同一请求重放（网络重试、连点）多插一行。
-- 结算请款的键是确定的（SETTLE-<订单号>，归还与买断共用一个），所以存下来加唯一约束，
-- 就从库这一层保证「一单只结算请款一次」，不依赖上层并发控制写对。
--
-- 可空：存量行没有键；UNIQUE 不约束 NULL，存量不受影响。
-- 本文件只用 MySQL 与 MariaDB 都认的语法。
-- ============================================================
ALTER TABLE pay_order
  ADD COLUMN idempotency_key VARCHAR(96) NULL COMMENT '请款幂等键（调用方给，确定性）；重复请求返回首单',
  ADD UNIQUE KEY uk_pay_order_idem (tenant_id, idempotency_key);
