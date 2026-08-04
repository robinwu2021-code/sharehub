-- wo_order：补回退留痕两列（reject_reason / reject_count）。
--
-- 背景：工单状态机新增两条回退边（WoStateMachine 的 REJECT / REWORK），端点
--   POST /api/ops/work-orders/{woNo}/reject  —— DISPATCHED/ACCEPTED/PROCESSING → CREATED
--   POST /api/ops/work-orders/{woNo}/rework  —— DONE → PROCESSING
-- 两者都强制填原因。原因必须落在聚合根上而不是只进 append 表：
-- 工单列表/看板要直出「最近一次为什么被退回」，若只存在 wo_dispatch/wo_handle 里，
-- 列表页每行都得再查一次子表 —— 这就是 close_reason 当初也冗余到本表的同一个理由（V8 §8.3）。
--
-- reject_count 是「转了几手」的快速判据：反复退回的工单是派单策略或工单描述有问题的信号，
-- 没有计数就只能靠人去数 wo_dispatch 的行数，做不成阈值告警。
-- 一律可空 + 默认 0（存量行没有退回历史）；服务端用 COALESCE(reject_count,0)+1 自增，
-- 因此即便存量行为 NULL 也不会算错。

SET NAMES utf8mb4;

ALTER TABLE wo_order
  ADD COLUMN IF NOT EXISTS reject_reason VARCHAR(256) NULL
    COMMENT '最近一次退回原因(reject 驳回退回 / rework 验收退回返工,均必填)',
  ADD COLUMN IF NOT EXISTS reject_count  INT          NOT NULL DEFAULT 0
    COMMENT '累计退回次数(驳回+返工),反复退回=派单或工单描述有问题';
