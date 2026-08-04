-- V32：wo_order.source_ref —— 一直被引用、从未建过的列。
--
-- V6/V7 的注释与 WoOpsServiceImpl 的幂等逻辑（告警/投诉转工单按 source_ref 查重）都指望它存在，
-- 但没有任何迁移建过：带 sourceNo 的开单/查重路径实际一直 Unknown column 500。
-- 本轮 pageRich 富装配按列名批读时把它撞了出来。
--
-- UNIQUE 保幂等：同一来源单据（告警号/投诉号/巡检周期站点键）只能开出一张工单，
-- 并发下重复转单由该约束拒绝（DuplicateKeyException → 409 语义）。NULL 不参与唯一性（手工开单）。
ALTER TABLE wo_order
  ADD COLUMN IF NOT EXISTS source_ref VARCHAR(64) NULL COMMENT '来源单据引用(告警号/投诉号/巡检键)，手工开单为 NULL';
ALTER TABLE wo_order
  ADD UNIQUE KEY IF NOT EXISTS uk_wo_source_ref (source_ref);
