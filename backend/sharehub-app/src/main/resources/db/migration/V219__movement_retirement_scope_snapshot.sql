-- 报废前的责任归属快照，不随原机柜或站点后续划拨改写；历史未知值保持NULL。
ALTER TABLE dev_movement_log
    ADD COLUMN scope_region_id VARCHAR(36) NULL COMMENT '动作发生时区域快照',
    ADD COLUMN scope_site_no VARCHAR(36) NULL COMMENT '动作发生时站点快照',
    ADD COLUMN scope_agent_no VARCHAR(36) NULL COMMENT '动作发生时商家快照';
