-- ============================================================
-- ShareHub · 定时推送：mkt_push 补齐排期/触达/幂等五列
--
-- 【怎么发现的】
-- api-align.py 的 B 类「字段对不上（运行期 undefined）」里，PushMessageVO 有 7 条
-- 是**前端有、后端无** —— 也就是界面正在渲染后端根本不返回的字段。查到数据库才
-- 发现不是「漏返回」，是**存储从来没建过**：前端照 mock 把整套定时推送做完了
-- （排期 / 人群 / 触达统计 / 幂等），后端只建了「立即发一条」。
--
-- 界面上的实际表现：营销页推送列表「操作人」列全空，
-- 「目标 undefined 人 / 成功 undefined 人」。不是显示 bug。
--
-- 【状态机也对不上】
-- 建表注释写的是 DRAFT/SENT 两态，而前端 PUSH_TRANSITIONS 是四态：
--   DRAFT --schedule--> SCHEDULED --send--> SENDING --finish--> SENT（终态不可重发）
-- 本次把列注释改成四态；状态值的强制在 service 层（同本仓其它状态机的做法）。
--
-- 【幂等键为什么要唯一索引】
-- 触达是**批量对外动作**：重复提交 = 真的把消息发两遍，口径同退款。
-- 幂等的意义全在这个唯一索引上 —— 只在应用层判「查一下有没有」的话，
-- 两个请求并发时两边都查不到，然后都发。
-- 允许 NULL：草稿与历史行没有幂等键，MySQL 唯一索引不约束 NULL。
-- ============================================================

ALTER TABLE mkt_push
  ADD COLUMN IF NOT EXISTS scheduled_at    DATETIME(3)     NULL COMMENT '排期发送时刻；NULL=立即发送',
  ADD COLUMN IF NOT EXISTS target_count    INT         NOT NULL DEFAULT 0 COMMENT '目标人数（发送时按人群规模落库）',
  ADD COLUMN IF NOT EXISTS success_count   INT         NOT NULL DEFAULT 0 COMMENT '成功触达人数（<= target_count）',
  ADD COLUMN IF NOT EXISTS operator_name   VARCHAR(64)     NULL COMMENT '操作人（谁发的，列表直出不再查一次）',
  ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64)     NULL COMMENT '幂等键：同一键只发一次',
  -- 人群拆成「类型 + 值」两列，而不是继续塞 audience JSON：
  -- 实体就是请求体，前端提交的 audienceType/audienceValue 在 MktPush 上没有对应字段
  -- → **被静默丢弃**，运营选的人群根本存不进去，而 mock 下一切正常。
  -- audience 保留为**派生的可读标签**（前端注释原话：「与 audienceType/audienceValue 同源」）。
  ADD COLUMN IF NOT EXISTS audience_type   VARCHAR(16) NOT NULL DEFAULT 'ALL'
      COMMENT 'ALL/MEMBER_LEVEL/SEGMENT/USER_LIST',
  ADD COLUMN IF NOT EXISTS audience_value  VARCHAR(255)    NULL
      COMMENT 'MEMBER_LEVEL→等级码；SEGMENT→segmentNo；USER_LIST→逗号分隔用户号；ALL→空';

-- 状态列注释改为四态（值本身不动，历史行仍是 DRAFT/SENT）
ALTER TABLE mkt_push
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
  COMMENT 'DRAFT/SCHEDULED/SENDING/SENT';

-- 幂等键唯一。**按 (tenant_id, idempotency_key)** 而不是全局：
-- 多租户下两家用同一个键是正常的，全局唯一会让第二家发不出去。
CREATE UNIQUE INDEX IF NOT EXISTS uk_push_idem ON mkt_push (tenant_id, idempotency_key);

-- 排期扫描要按「到点且还没发」取，两列一起走索引。
-- 将来这条扫描会挂成共用调度器的 JobHandler（v4/07）；在那之前是手动端点，
-- 两者读的是同一个查询，接线时业务代码不用改。
CREATE INDEX IF NOT EXISTS idx_push_due ON mkt_push (status, scheduled_at);
