-- 容器/槽位的设备类型维度（ADR-018 §五，M1 时漏做的部分）
--
-- ADR-018 定的是「容器(dev_cabinet)与槽位(dev_slot)保持设备无关，加 device_type 维度」，
-- 但 V16 只给 ord_order 加了这一列 —— 设备侧漏了。
-- 加实体字段时才炸出来（Unknown column 'device_type'），这正是
-- entity-column-diff 卡口的价值：实体声明与库结构必须一致。
--
-- 槽位语义（ADR-018）：充电宝=仓位有实物、充电桩=枪无实物、储物柜=格口无实物。

SET NAMES utf8mb4;

ALTER TABLE dev_cabinet
  ADD COLUMN IF NOT EXISTS device_type VARCHAR(32) NULL COMMENT '设备类型 POWERBANK/EV_PILE/LOCKER；容器本身设备无关，靠这列区分';

ALTER TABLE dev_slot
  ADD COLUMN IF NOT EXISTS device_type VARCHAR(32) NULL COMMENT '设备类型，随所属容器',
  ADD COLUMN IF NOT EXISTS slot_kind   VARCHAR(16) NULL COMMENT '槽位形态 BAY(仓位)/CONNECTOR(枪)/CELL(格口)';

-- 存量全是充电宝
UPDATE dev_cabinet SET device_type = 'POWERBANK' WHERE device_type IS NULL;
UPDATE dev_slot SET device_type = 'POWERBANK', slot_kind = 'BAY' WHERE device_type IS NULL;

-- 按设备类型筛是运营端主查询
ALTER TABLE dev_cabinet
  ADD KEY IF NOT EXISTS idx_cabinet_device_type (tenant_id, device_type, status);
