-- 订单拆表：共性主表 + 类型扩展表（ADR-018 · 设计v3-数据库变更 §一、§二）
--
-- 目标：新增设备类型（充电桩/储物柜）时，资金域（分润/账务/发票/结算）**一行不改**。
-- 判据：这四域只 join ord_order，永远不碰扩展表。做不到就是共性列抽错了。
--
-- 迁移策略：RENAME 而非「新建+搬数据+删旧表」——
-- RENAME 是原子的元数据操作，136 行数据零拷贝、零停机窗口；
-- 新建搬数据则要处理搬运期间的并发写入。

SET NAMES utf8mb4;

-- ─────────────────── 一、设备类型注册表 ───────────────────
-- 类型差异用**数据**描述，不用 if/else 散在代码里。
-- 这张表就是「加一种设备要改哪些东西」的清单。
CREATE TABLE IF NOT EXISTS md_device_type (
  id                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  type_code                 VARCHAR(32)  NOT NULL COMMENT 'POWERBANK / EV_PILE / LOCKER',
  name_zh                   VARCHAR(64)  NOT NULL,
  name_en                   VARCHAR(64)  NULL,
  name_ar                   VARCHAR(64)  NULL,
  has_movable_item          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '槽位里是否有平台资产：充电宝1，桩/柜0',
  item_table                VARCHAR(64)  NULL COMMENT '物品台账表名；has_movable_item=0 时为 NULL',
  order_ext_table           VARCHAR(64)  NOT NULL COMMENT '订单扩展表名',
  default_metering          VARCHAR(16)  NOT NULL DEFAULT 'MINUTE' COMMENT 'MINUTE/KWH/COUNT',
  reservation_target        VARCHAR(16)  NOT NULL DEFAULT 'CABINET' COMMENT '预约粒度 CABINET/SLOT',
  supports_return_elsewhere TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否支持异地归还',
  command_family            VARCHAR(32)  NOT NULL COMMENT '驱动指令族',
  status                    VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
  created_at                DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by                VARCHAR(36)  NULL,
  updated_at                DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by                VARCHAR(36)  NULL,
  version                   BIGINT       NOT NULL DEFAULT 0,
  deleted                   TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_device_type_code (type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备类型注册表（全局，无 tenant_id）';

-- 首批三类。ON DUPLICATE 保证可重跑（本项目种子 INSERT 撞过一次主键）。
INSERT INTO md_device_type
  (type_code, name_zh, name_en, has_movable_item, item_table, order_ext_table,
   default_metering, reservation_target, supports_return_elsewhere, command_family)
VALUES
  ('POWERBANK','充电宝','Power Bank',1,'dev_powerbank','ord_rent_ext','MINUTE','CABINET',1,'POWERBANK'),
  ('EV_PILE','充电桩','EV Charger',  0,NULL,           'ord_charge_ext','KWH',  'SLOT',   0,'EV_PILE'),
  ('LOCKER','储物柜','Locker',       0,NULL,           'ord_locker_ext','MINUTE','SLOT',  0,'LOCKER')
ON DUPLICATE KEY UPDATE type_code = type_code;

-- ─────────────────── 二、ord_rent → ord_order ───────────────────
-- IF EXISTS 让本步可重跑：迁移中途失败时 DDL 已提交无法回滚，
-- 重跑时 ord_rent 已不存在。本项目在 V8 的 CHANGE COLUMN 上踩过同样的坑。
RENAME TABLE IF EXISTS ord_rent TO ord_order;

-- 共性列。全部可空 —— 存量 136 行已有数据，NOT NULL 无默认值会插入失败。
ALTER TABLE ord_order
  ADD COLUMN IF NOT EXISTS device_type    VARCHAR(32)   NULL COMMENT '设备类型，一等维度',
  ADD COLUMN IF NOT EXISTS sub_status     VARCHAR(24)   NULL COMMENT '类型子状态（DISPENSING/PREPARING/IDLE）；**资金侧不读**',
  ADD COLUMN IF NOT EXISTS slot_index     INT           NULL COMMENT '槽位：宝=仓位 桩=枪 柜=格口',
  ADD COLUMN IF NOT EXISTS started_at     DATETIME(3)   NULL COMMENT '业务开始（充电宝=借出时刻）',
  ADD COLUMN IF NOT EXISTS ended_at       DATETIME(3)   NULL COMMENT '业务结束',
  ADD COLUMN IF NOT EXISTS amount         DECIMAL(18,2) NULL COMMENT '应收合计（原 fee_amount 的共性化）',
  ADD COLUMN IF NOT EXISTS auth_no        VARCHAR(36)   NULL COMMENT '预授权单（逻辑引用 pay_auth）',
  ADD COLUMN IF NOT EXISTS source_channel VARCHAR(16)   NULL COMMENT 'APP/MINI/H5/INTERCONNECT',
  ADD COLUMN IF NOT EXISTS partner_no     VARCHAR(36)   NULL COMMENT '互联互通伙伴（intc_partner）',
  ADD COLUMN IF NOT EXISTS price_snapshot JSON          NULL COMMENT '计价方案的**展开结构**快照，非 plan_no 引用 —— 改价不影响在途单';

-- 回填存量：全部是充电宝单
UPDATE ord_order SET device_type = 'POWERBANK' WHERE device_type IS NULL;
UPDATE ord_order SET amount = fee_amount WHERE amount IS NULL AND fee_amount IS NOT NULL;
UPDATE ord_order SET source_channel = 'APP' WHERE source_channel IS NULL;
-- rent_start_at/rent_end_at 是 VARCHAR(32)，**实际存的是 ISO-8601 且格式不统一**：
--   2026-07-11T12:00:00Z          120 行（秒级）
--   2026-07-13T00:47:13.366437Z    37 行（带 6 位微秒）
-- 首版按 'yyyy-MM-dd HH:mm:ss' 假设 → Incorrect datetime value；
-- 二版归一 T/Z 后仍炸 → Data truncation（尾部微秒没处理）。
--
-- **教训：字符串存时间的列，格式必然会漂**。同一列三种写法就是 db-design
-- 规定「时间用 DATETIME 而非 VARCHAR」的实证理由。
--
-- 解法：归一 T/Z 后 **LEFT(...,19)** 截到「秒」，一次覆盖所有变体；
-- 再用 REGEXP 兜底过滤不合法行（留 NULL，不让一条脏数据阻断 157 行迁移）。
UPDATE ord_order
   SET started_at = STR_TO_DATE(LEFT(REPLACE(REPLACE(rent_start_at,'T',' '),'Z',''), 19),
                                '%Y-%m-%d %H:%i:%s')
 WHERE started_at IS NULL
   AND rent_start_at REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}:[0-9]{2}';

UPDATE ord_order
   SET ended_at = STR_TO_DATE(LEFT(REPLACE(REPLACE(rent_end_at,'T',' '),'Z',''), 19),
                              '%Y-%m-%d %H:%i:%s')
 WHERE ended_at IS NULL
   AND rent_end_at REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}:[0-9]{2}';

-- 按设备类型筛是运营端主查询，补索引
ALTER TABLE ord_order
  ADD KEY IF NOT EXISTS idx_ord_type (tenant_id, device_type, status, created_at);

-- ─────────────────── 三、类型扩展表 ───────────────────
-- 扩展表**不带** tenant_id/version/deleted：生命周期完全依附主表，
-- 由主表的租户与软删覆盖（本项目 @TableName(excludeProperty) 的既有用法）。
CREATE TABLE IF NOT EXISTS ord_rent_ext (
  order_no          VARCHAR(36) NOT NULL COMMENT '主表业务键，1:1',
  powerbank_no      VARCHAR(36) NULL COMMENT '借走的充电宝',
  return_cabinet_no VARCHAR(36) NULL COMMENT '归还柜机（可异地）',
  return_slot_index INT         NULL,
  buyout            TINYINT(1)  NULL COMMENT '是否买断（丢失）',
  duration_min      INT         NULL COMMENT '时长（分钟）',
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (order_no),
  KEY idx_rentext_pb (powerbank_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单扩展-充电宝';

CREATE TABLE IF NOT EXISTS ord_charge_ext (
  order_no      VARCHAR(36)    NOT NULL,
  connector_no  VARCHAR(36)    NULL COMMENT '枪/连接器',
  energy_kwh    DECIMAL(10,3)  NULL COMMENT '充电量；三位小数是行业口径',
  start_soc     TINYINT        NULL,
  end_soc       TINYINT        NULL,
  peak_power_kw DECIMAL(8,2)   NULL,
  meter_start   DECIMAL(12,3)  NULL COMMENT '起始表底数',
  meter_stop    DECIMAL(12,3)  NULL COMMENT '终止表底数 —— **结算只认它**，过程累计仅展示',
  stop_reason   VARCHAR(16)    NULL COMMENT 'FULL/USER/FAULT/BALANCE/REMOTE',
  idle_min      INT            NULL COMMENT '充满后占位分钟',
  created_at    DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单扩展-充电桩';

CREATE TABLE IF NOT EXISTS ord_locker_ext (
  order_no     VARCHAR(36)  NOT NULL,
  cell_index   INT          NULL COMMENT '格口',
  access_code  VARCHAR(16)  NULL COMMENT '取件码',
  overtime_min INT          NULL COMMENT '超期占用分钟',
  item_note    VARCHAR(128) NULL COMMENT '存物备注',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单扩展-储物柜';

-- 存量充电宝单的专属列搬入扩展表（主表同名列暂留，验证通过后的版本再 DROP —— 删列不可回退）
INSERT INTO ord_rent_ext (order_no, powerbank_no, return_cabinet_no, buyout, duration_min)
SELECT order_no, powerbank_no, return_cabinet_no, buyout, duration_min
FROM ord_order
WHERE device_type = 'POWERBANK'
ON DUPLICATE KEY UPDATE order_no = ord_rent_ext.order_no;
