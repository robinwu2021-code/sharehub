-- 事务性发件箱（ADR-019 · 设计v3-数据库变更 §七）
--
-- 为什么单体期就要建：拆服务后「业务落库」与「发消息」必须原子，否则会出现
-- 订单结算了但分润事件丢了 —— 账永远对不上，且无法自愈（没有任何记录说明该发而未发）。
-- 它是拆分的**前置条件**，不是拆分时才加的东西。
--
-- 追加表：无 version / deleted。事件是既成事实，只允许改投递状态，不允许改内容也不允许删。

CREATE TABLE IF NOT EXISTS sys_outbox (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_no        VARCHAR(36)  NOT NULL COMMENT '事件编号，前缀 EVT',
  tenant_id       VARCHAR(36)  NULL     COMMENT '租户；跨租户的平台级事件为 NULL',
  aggregate_type  VARCHAR(64)  NOT NULL COMMENT '聚合类型，如 AgtAssignment / OrdOrder',
  aggregate_id    VARCHAR(64)  NOT NULL COMMENT '聚合业务键，如 ASG0001 / ORD0001',
  event_type      VARCHAR(64)  NOT NULL COMMENT '事件类型，如 ASSET_ASSIGNED / ORDER_SETTLED',
  payload         JSON         NOT NULL COMMENT '事件载荷。**必须自带消费方所需全部字段** —— 让消费方回查等于把同步调用藏进事件',
  status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED',
  retry_count     INT          NOT NULL DEFAULT 0,
  next_retry_at   DATETIME(3)  NULL     COMMENT '下次重试时间；PENDING 且 <= now 才会被取走',
  last_error      VARCHAR(512) NULL,
  sent_at         DATETIME(3)  NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by      VARCHAR(36)  NULL,
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(36)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbox_event_no (event_no),
  -- 轮询热路径：按状态 + 到期时间取待投递的一批
  KEY idx_outbox_poll (status, next_retry_at, id),
  -- 排错路径：按聚合追溯某单据发过哪些事件
  KEY idx_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务性发件箱';
