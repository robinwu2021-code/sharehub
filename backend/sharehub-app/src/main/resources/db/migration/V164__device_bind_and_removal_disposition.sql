-- 出库池第三步：绑定 / 解绑 / 撤机去向（技术设计 §3.4 / §3.5 / §2.6）。
--
-- 写法：不用 MariaDB 的 `ADD COLUMN IF NOT EXISTS`，本机 MySQL 9.7 与 MariaDB 都能跑。

-- ── 机柜：商家只由绑定的点位派生（I3）──
-- 此前撤机「保留 agent_no（划拨归属）」，于是没绑点位的柜子也挂着商家号 —— 有的已经回了仓。
-- 现在商家归属看出库池（issued_agent_no），agent_no 只在绑了点位时才有。存量清掉。
UPDATE dev_cabinet SET agent_no = NULL WHERE location_no IS NULL AND agent_no IS NOT NULL AND deleted = 0;

-- 绑了商家的点位，就必须在某个商家的出库池里（平台直营站点不经出库池，agent_no 为空，放行 —— I5）。
-- 只约束「在不在池里」，不约束「池子的商家 = 点位的商家」：后者由绑定端点保证（I2），
-- 整站换商家时两列同事务改。
ALTER TABLE dev_cabinet
  ADD CONSTRAINT chk_cab_bound_issued CHECK (location_no IS NULL OR agent_no IS NULL OR issued_agent_no IS NOT NULL);

-- ── 撤机去向 ──
ALTER TABLE wo_order
  ADD COLUMN removal_disposition VARCHAR(24) NULL
      COMMENT '撤机去向词表：RETURN_TO_POOL/RETURN_TO_WAREHOUSE（RETURN_TO_POOL=回同一商家出库池，可再绑别的点位；RETURN_TO_WAREHOUSE=生成回仓单）；仅撤机单有值，空按回仓' AFTER type;
