-- ============================================================
-- V194 质检补强（docs/technical/独立进销存/D §2.5，PB-C2 ~ C4）
--   rule_snapshot：判定时的阈值快照 —— 改了系统参数，历史结论仍能解释
--   intake_no   ：在哪张到仓待检单上做的质检
--   consumed_by ：用掉这份放行凭证的待检单 —— 一份「通过」只能入库一次；
--                  回仓入库只认本单上新做的质检（旧的通过不能复用）
-- ============================================================
ALTER TABLE dev_qc_record
  ADD COLUMN rule_snapshot VARCHAR(255) NULL COMMENT '判定阈值快照（JSON）' AFTER result,
  ADD COLUMN intake_no     VARCHAR(36)  NULL COMMENT '所属到仓待检单号' AFTER rule_snapshot,
  ADD COLUMN consumed_by   VARCHAR(36)  NULL COMMENT '用掉本放行凭证的待检单号；非空即已用' AFTER intake_no,
  ADD KEY idx_qc_intake (intake_no);
