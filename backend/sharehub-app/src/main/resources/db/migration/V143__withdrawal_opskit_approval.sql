-- 提现送 OpsKit 审批（M5-1 试点，docs/technical/v4/16-工作流与工单/23-M5-ShareHub业务接线方案.md §9.2）。
-- 与提现状态机（status）正交：status 说钱走到哪，approval_state 只说「审批这件事」在不在 OpsKit、卡在哪。
-- LOCAL 模式（sharehub.flow.withdrawal.engine=LOCAL，默认）下四列都空，行为与此前完全一致。
ALTER TABLE stl_withdrawal
  ADD COLUMN approval_state VARCHAR(20) NULL
    COMMENT '审批进度：SUBMITTING 送审中 / IN_REVIEW 审批中 / SUBMIT_FAILED 送审失败 / APPROVED_BLOCKED 已通过未生效；空=本地审批或已结束',
  ADD COLUMN approval_no VARCHAR(40) NULL COMMENT 'OpsKit 审批单号（结束后保留，便于追溯）',
  ADD COLUMN approval_round INT NULL COMMENT '送审轮次，从 1 起；重新送审加一（进 OpsKit 幂等键）',
  ADD COLUMN approval_note VARCHAR(255) NULL COMMENT '送审失败 / 已通过未生效的原因';
