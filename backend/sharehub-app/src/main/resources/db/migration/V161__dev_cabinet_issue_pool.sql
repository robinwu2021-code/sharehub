-- 设备出库池（用户 2026-10-28：绑定商家先出库，出库必须选商家，绑定只从该商家的出库池里选）。
-- 技术设计 §2.1；库存（仓库、调拨单）暂缓，可能由独立服务实现 —— 本步只在设备上记「出库给了哪个商家」，
-- 出库 / 退回是设备上的直接动作，将来库存服务调同一入口。
--
-- 存储态不扩：出库池里的柜子仍是 IN_STOCK（warehouse_no 为空、location_no 为空）。
-- 「在仓 / 在出库池未绑定 / 已绑定」由列派生。
--
-- 写法：不用 MariaDB 的 `ADD COLUMN IF NOT EXISTS`，本机 MySQL 9.7 与 MariaDB 都能跑。

ALTER TABLE dev_cabinet
  ADD COLUMN issued_agent_no VARCHAR(36) NULL COMMENT '出库池归属：出库给了哪个商家；退回仓库后清空' AFTER warehouse_no,
  ADD COLUMN issued_at       DATETIME(3) NULL COMMENT '进入出库池的时刻' AFTER issued_agent_no,
  ADD COLUMN issued_by       VARCHAR(36) NULL COMMENT '经办人（运营代签，一期不经商家签收）' AFTER issued_at;

-- 存量：有商家、且不在仓的柜子视为已在该商家的出库池（已绑定的，或撤机后保留了 agent_no 的）。
-- 在仓的不回填：它们回到了仓库，不再属于任何商家的出库池。
UPDATE dev_cabinet
   SET issued_agent_no = agent_no, issued_at = COALESCE(bound_at, updated_at)
 WHERE agent_no IS NOT NULL AND warehouse_no IS NULL AND deleted = 0;

-- 数据范围锚点：已绑定看 agent_no，未绑定看出库池 —— 商家在「我的设备」里能看到待安装的设备。
-- 生成列而不是同步逻辑：同步一旦漏一处就是静默泄露或静默不可见，生成列没有「忘了写」这种可能（同 V58）。
ALTER TABLE dev_cabinet
  ADD COLUMN scope_agent_no VARCHAR(36) GENERATED ALWAYS AS (COALESCE(agent_no, issued_agent_no)) STORED
      COMMENT '数据范围锚点（派生）：已绑定 = agent_no，未绑定 = issued_agent_no',
  ADD KEY idx_cab_issued (tenant_id, issued_agent_no, status),
  ADD KEY idx_cab_scope_agent (tenant_id, scope_agent_no, status),
  -- I1：在仓与在出库池互斥
  ADD CONSTRAINT chk_cab_pool_vs_wh CHECK (warehouse_no IS NULL OR issued_agent_no IS NULL);
