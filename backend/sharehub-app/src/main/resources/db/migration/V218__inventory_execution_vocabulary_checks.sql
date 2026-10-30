-- 新表已经建立，后续用兼容迁移约束词表，不修改已执行的V212—V217。
SET NAMES utf8mb4;

ALTER TABLE inv_operation
  ADD CONSTRAINT chk_inv_operation_engine CHECK (BINARY engine IN ('LOCAL','SERVICE')),
  ADD CONSTRAINT chk_inv_operation_phase CHECK (BINARY phase IN ('PREPARED','OUT_POSTED','IN_POSTED','COMPLETED','NEEDS_REVIEW'));
ALTER TABLE inv_operation_step
  ADD CONSTRAINT chk_inv_operation_step_status CHECK (BINARY status IN ('PENDING','UNKNOWN','COMPLETED','FAILED'));
ALTER TABLE inv_purchase
  ADD CONSTRAINT chk_inv_purchase_status CHECK (BINARY status IN ('DRAFT','ORDERED','PART_RECEIVED','RECEIVED','CLOSED','CANCELLED'));
ALTER TABLE inv_purchase_line
  ADD CONSTRAINT chk_inv_purchase_item_type CHECK (BINARY item_type IN ('CABINET','POWERBANK','MATERIAL'));
ALTER TABLE dev_delivery
  ADD CONSTRAINT chk_dev_delivery_status CHECK (BINARY status IN ('ISSUED','CLAIMED','ACCEPTED','RETURNED','CANCELLED')),
  ADD CONSTRAINT chk_dev_delivery_handover CHECK (handover_mode IS NULL OR BINARY handover_mode IN ('IN_PERSON','CARRIER'));
ALTER TABLE dev_delivery_item
  ADD CONSTRAINT chk_dev_delivery_item_type CHECK (BINARY item_type IN ('CABINET','POWERBANK'));
ALTER TABLE dev_repair_detail
  ADD CONSTRAINT chk_dev_repair_mode CHECK (BINARY repair_mode IN ('ONSITE','WAREHOUSE','VENDOR')),
  ADD CONSTRAINT chk_dev_repair_test_result CHECK (test_result IS NULL OR BINARY test_result IN ('PASS','FAIL'));
ALTER TABLE sys_export_task
  ADD CONSTRAINT chk_export_task_format CHECK (BINARY format IN ('XLSX','PDF')),
  ADD CONSTRAINT chk_export_task_status CHECK (BINARY status IN ('PENDING','RUNNING','COMPLETED','FAILED','EXPIRED'));
