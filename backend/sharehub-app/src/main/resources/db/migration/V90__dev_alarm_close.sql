-- 告警关闭：建列。
--
-- 此前 AlarmStateMachine 有 OPEN→CLOSED / ACKED→CLOSED 两条边，
-- 而**主源码里没有任何一处发 "CLOSE" 事件**（只有测试里有），
-- AlarmService 六个方法里也没有 close ⇒ 告警只能 OPEN→ACKED 然后停住，
-- dev_alarm 的 ACKED 行只增不减，「还有多少没处理」这个数从此说不清。
--
-- 关闭必须带原因，与「验收关单必须给结论」同一口径。
-- 更实际的理由：**「误报率」这个数只有在关闭时记了原因才算得出来** ——
-- 不记就只知道「关了 300 条」，不知道其中多少是设备真故障、多少是规则太敏感。
-- 规则调不动，告警就会一直吵，吵到没人看。
--
-- 三列都可空：存量已关闭的行（若有）无从补，不为它们编值。
ALTER TABLE dev_alarm
  ADD COLUMN IF NOT EXISTS close_reason VARCHAR(16) NULL
      COMMENT 'RESOLVED/FALSE_ALARM/SELF_HEALED；关闭时必填',
  ADD COLUMN IF NOT EXISTS close_note   VARCHAR(512) NULL
      COMMENT '关闭备注；原因之外的补充，可空',
  ADD COLUMN IF NOT EXISTS closed_by    VARCHAR(36)  NULL
      COMMENT '关闭人（员工号）',
  ADD COLUMN IF NOT EXISTS closed_at    DATETIME(3)  NULL
      COMMENT '关闭时间';
