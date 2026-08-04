-- 计价重构：方案 → 费用项 → 阶梯（ADR-018 · 设计v3-数据库变更 §三）
--
-- 现 price_plan 把时长计费写死在四个列上（free_minutes/unit_minutes/unit_price/cap_daily），
-- 充电桩装不进去 —— 它按 kWh 计、要阶梯电价、要峰谷分时、且电费与服务费必须分列。
--
-- 三级模型：一个方案有多个费用项，一个费用项有多段阶梯。
-- 单段阶梯即「无阶梯」——**不为简单场景另设一条无阶梯路径**，一种表达方式减少分支。

SET NAMES utf8mb4;

-- ─────────────────── 一、方案加设备类型 ───────────────────
ALTER TABLE price_plan
  ADD COLUMN IF NOT EXISTS device_type VARCHAR(32) NULL COMMENT '适用设备类型；NULL=通用';

UPDATE price_plan SET device_type = 'POWERBANK' WHERE device_type IS NULL;

-- ─────────────────── 二、费用项 ───────────────────
CREATE TABLE IF NOT EXISTS price_plan_item (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  item_no     VARCHAR(36)   NOT NULL COMMENT '业务键，前缀 PI',
  tenant_id   VARCHAR(36)   NULL,
  plan_no     VARCHAR(36)   NOT NULL COMMENT '所属方案',
  item_type   VARCHAR(16)   NOT NULL COMMENT 'TIME_FEE/ENERGY_FEE/SERVICE_FEE/IDLE_FEE/HOLD_FEE',
  metering    VARCHAR(16)   NOT NULL DEFAULT 'MINUTE' COMMENT 'MINUTE/KWH/COUNT',
  free_qty    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '免费额度（分钟/度/次）；IDLE_FEE 用它表达宽限期',
  unit_qty    DECIMAL(12,3) NOT NULL DEFAULT 1 COMMENT '计费步长，如每 30 分钟、每 1 度',
  cap_daily   DECIMAL(18,2) NULL COMMENT '单项日封顶',
  cap_total   DECIMAL(18,2) NULL COMMENT '单项总封顶',
  rounding    VARCHAR(16)   NOT NULL DEFAULT 'CEIL' COMMENT 'CEIL/FLOOR/HALF_UP —— **必须显式**，四舍五入口径是对账争议高发区',
  sort        INT           NOT NULL DEFAULT 0 COMMENT '出账顺序（账单展示与发票行序）',
  status      VARCHAR(16)   NOT NULL DEFAULT 'ENABLED',
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by  VARCHAR(36)   NULL,
  updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(36)   NULL,
  version     BIGINT        NOT NULL DEFAULT 0,
  deleted     TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_item_no (item_no),
  KEY idx_pi_plan (plan_no, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计价费用项';

-- ─────────────────── 三、阶梯 ───────────────────
CREATE TABLE IF NOT EXISTS price_ladder (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ladder_no  VARCHAR(36)   NOT NULL COMMENT '业务键，前缀 PL',
  tenant_id  VARCHAR(36)   NULL,
  item_no    VARCHAR(36)   NOT NULL COMMENT '所属费用项',
  seq        INT           NOT NULL COMMENT '阶梯序，从 1 起',
  from_qty   DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '区间下界（含）',
  to_qty     DECIMAL(12,3) NULL COMMENT '区间上界（不含）；NULL=无上限',
  unit_price DECIMAL(18,4) NOT NULL COMMENT '该区间单价。四位小数 —— 电价常见 0.6543/度',
  created_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by VARCHAR(36)   NULL,
  updated_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by VARCHAR(36)   NULL,
  version    BIGINT        NOT NULL DEFAULT 0,
  deleted    TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_ladder_no (ladder_no),
  UNIQUE KEY uk_ladder_item_seq (item_no, seq),
  KEY idx_pl_item (item_no, from_qty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计价阶梯';

-- ─────────────────── 四、分时作用到费用项 ───────────────────
ALTER TABLE price_schedule
  ADD COLUMN IF NOT EXISTS item_no VARCHAR(36) NULL COMMENT '作用的费用项；NULL=作用于方案的全部项',
  ADD COLUMN IF NOT EXISTS mode    VARCHAR(16) NULL COMMENT 'MULTIPLY 系数 / REPLACE 替换价';

UPDATE price_schedule SET mode = 'MULTIPLY' WHERE mode IS NULL;

-- ─────────────────── 五、存量方案平移 ───────────────────
-- 现 price_plan 的四个列 → 一条 TIME_FEE item + 一段无上限阶梯，**语义零变化**。
-- 平移后须用影子重算逐单比对，通过才允许切换计费入口。
INSERT INTO price_plan_item
  (item_no, tenant_id, plan_no, item_type, metering, free_qty, unit_qty, cap_daily, rounding, sort)
SELECT CONCAT('PI', LPAD(p.id, 8, '0')), p.tenant_id, p.plan_no,
       'TIME_FEE', 'MINUTE',
       COALESCE(p.free_minutes, 0), COALESCE(NULLIF(p.unit_minutes, 0), 1),
       p.cap_daily, 'CEIL', 1
FROM price_plan p
WHERE p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM price_plan_item i WHERE i.plan_no = p.plan_no AND i.item_type = 'TIME_FEE');

INSERT INTO price_ladder (ladder_no, tenant_id, item_no, seq, from_qty, to_qty, unit_price)
SELECT CONCAT('PL', LPAD(p.id, 8, '0')), p.tenant_id, CONCAT('PI', LPAD(p.id, 8, '0')),
       1, 0, NULL, COALESCE(p.unit_price, 0)
FROM price_plan p
WHERE p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM price_ladder l WHERE l.item_no = CONCAT('PI', LPAD(p.id, 8, '0')));
