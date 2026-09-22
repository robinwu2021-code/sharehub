-- V36：dev_cabinet 补 DevCabinet 实体存在但表里没有的两列。
--
-- DevCabinet.locationName / availableCount 在实体里，但 dev_cabinet 表没有 ——
-- ReportMappers.snapshot()（首页看板 SQL）里的 c.location_name 会直接 500。
--
-- 同 V35 原意：冗余快照，由 CabinetService 写入时落入。

ALTER TABLE dev_cabinet
  ADD COLUMN IF NOT EXISTS location_name    VARCHAR(128) NULL COMMENT '点位名快照(冗余·不随源改名回溯)',
  ADD COLUMN IF NOT EXISTS available_count  INT          NULL COMMENT '可用仓位数快照(与 slot_total 配对)';
