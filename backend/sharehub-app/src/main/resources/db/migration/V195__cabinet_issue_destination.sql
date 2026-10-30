-- ============================================================
-- V195 出库目的地与签收（docs/technical/独立进销存/D §1.4，PB-D1 / D2 / D4；用户 2026-10-29：
--   出库选商家与内部接收人，有站点就到站点，没有就到商家；出错在出库处调整）
--   issued_site_no   ：出库到的站点 —— 非空时只能绑该站点的点位；空 = 到商家，可绑该商家任一站点
--   issue_receiver_no：内部接收人（员工号）—— 由他签收
--   issue_claimed_at ：签收时刻 —— 空 = 已发货待签收，不能绑定
-- 存量出库池设备视为已签收（出库即交付是 V161 的口径）。
-- ============================================================
ALTER TABLE dev_cabinet
  ADD COLUMN issued_site_no    VARCHAR(36) NULL COMMENT '出库目的站点；空表示到商家' AFTER issued_by,
  ADD COLUMN issue_receiver_no VARCHAR(36) NULL COMMENT '出库内部接收人员工号' AFTER issued_site_no,
  ADD COLUMN issue_claimed_at  DATETIME(3) NULL COMMENT '接收人签收时刻；空表示已发货待签收' AFTER issue_receiver_no,
  ADD KEY idx_cabinet_issue_receiver (issue_receiver_no, issue_claimed_at);

UPDATE dev_cabinet SET issue_claimed_at = issued_at WHERE issued_agent_no IS NOT NULL AND issue_claimed_at IS NULL;
