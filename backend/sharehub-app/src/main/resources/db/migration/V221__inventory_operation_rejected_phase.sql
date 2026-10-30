-- 库存操作新增终态 REJECTED：在发出任何库存服务请求之前就被业务拒绝（预检不通过、仓库冻结等）。
-- 此前这类操作和「已发出远端请求后本地回滚」一样记 NEEDS_REVIEW，可它们没有任何东西需要查回，
-- 会一直算作未完成：切换库存引擎永远被挡（F09），执行异常列表也会被淹没。同键原样重提仍可执行。
SET NAMES utf8mb4;

ALTER TABLE inv_operation
  MODIFY COLUMN phase VARCHAR(32) NOT NULL DEFAULT 'PREPARED' COMMENT 'PREPARED / OUT_POSTED / IN_POSTED / COMPLETED / NEEDS_REVIEW / REJECTED',
  DROP CHECK chk_inv_operation_phase,
  ADD CONSTRAINT chk_inv_operation_phase CHECK (BINARY phase IN ('PREPARED','OUT_POSTED','IN_POSTED','COMPLETED','NEEDS_REVIEW','REJECTED'));

-- 存量：待复核但一步远端都没登记过的，就是发请求前被拒的（步骤在发请求之前先落 UNKNOWN，见 InventoryOperationJournal.step）
UPDATE inv_operation o
   SET o.phase = 'REJECTED',
       o.last_error = 'Rejected before any inventory-service request; nothing to recover'
 WHERE o.phase = 'NEEDS_REVIEW'
   AND NOT EXISTS (SELECT 1 FROM inv_operation_step s WHERE s.operation_no = o.operation_no);
