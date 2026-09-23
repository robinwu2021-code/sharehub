-- 事件消费去重（B7 跨进程地基 · TDD-cross-process-foundation）
--
-- Outbox 的投递语义是**至少一次**，不是恰好一次：投递成功但回写 SENT 之前宕机，
-- 下一轮轮询会把同一条再投一遍。做成「恰好一次」要分布式事务，代价远大于收益。
-- 所以约定是「至少一次 + 消费端去重」—— 本表就是去重的那一半。
--
-- **以数据库唯一约束为权威**，不靠应用层的 check-then-act：
-- 先 SELECT 再 INSERT 在并发下必漏（两个线程都查到「没消费过」）。
-- 正确姿势是直接 INSERT，撞唯一键即说明别人已经处理过，这一次跳过。
--
-- 为什么键是 (event_no, handler) 而不是 event_no：
-- 一个事件会被多个消费者处理（订单结算事件 → 分润、通知、报表各一个），
-- 只按 event_no 去重会让第二个消费者被当成重复而永远收不到事件。

CREATE TABLE IF NOT EXISTS sys_event_consumed (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_no     VARCHAR(36)  NOT NULL COMMENT '事件编号，对应 sys_outbox.event_no',
  handler      VARCHAR(128) NOT NULL COMMENT '消费者标识，一般是实现类名',
  event_type   VARCHAR(64)      NULL COMMENT '冗余，排错时不必回查 outbox',
  consumed_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- 去重闸门本身。UNIQUE 不是「加个索引快一点」，它是并发正确性的唯一保证
  UNIQUE KEY uk_event_consumed (event_no, handler),
  -- 清理路径：本表只增不减，按时间删旧（保留期见 outbox-purge 任务）
  KEY idx_event_consumed_at (consumed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件消费去重（至少一次投递的消费端闸门）';

-- 投递到达上限后的终态。此前只有 PENDING/SENT/FAILED，
-- FAILED 会被无限重投 —— 一个没有死信状态的重投队列，最终会变成无人看的失败堆。
ALTER TABLE sys_outbox
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
  COMMENT 'PENDING 待投递 / SENT 已送达 / FAILED 待重投 / DEAD 超过重试上限，需人工介入';
