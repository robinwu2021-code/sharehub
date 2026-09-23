-- ============================================================
-- powerbank · 取价只保留「适用范围」一种机制（ADR-028 落地第 1+2 步 · TDD-P1）
--
-- 【动手前实测到的四处缺陷】
--  1. 站点规则不看设备类型 —— L2 按摩椅会按充电宝价收
--  2. `price_plan_scope`（适用范围）写了没人读 —— 取价引擎读的是 `price_rule`
--  3. 时段倍率配了不生效 —— `charge(gross, null, …)`，倍率位恒为 null
--  4. **下单根本没传站点** —— `resolve(POWERBANK, null, null)`
--
-- 第 4 条是本次动手才发现的，它把第 2 条的严重性抬高一级：不是「两套机制并存、
-- 生效的是另一套」，而是**两套都没生效** —— 今天站点级差价完全不存在，两个菜单都是摆设。
--
-- 【为什么不加 level 列】
-- `scope_type` 就是层，语义没变，只是取值域从 SITE/SCENE/ALL 扩到八档。
-- 再加一个同义的 `level` 列要动实体、服务、前端三处，换不来任何表达力。
--
-- 【为什么 UK 要换】
-- 旧 UK (plan_no, scope_type, scope_ref) 保证的是「一个方案不重复登记同一范围」；
-- 取价要的是反过来那条 ——「同一范围只能有一个启用中的方案」，否则两个方案抢同一个站点，
-- 取价就得靠 priority 猜。新 UK 落在范围侧（不含 plan_no），这才是「取价必然唯一」的保证。
-- ============================================================
SET NAMES utf8mb4;

-- ---------- 一、price_plan_scope 扩到位（ADR-028 §一）----------
-- 一次扩到位而不是分批：列空着不花钱，迁移是要停服的（链条方案 P2：事后补等于再迁一次）。
ALTER TABLE price_plan_scope
  ADD COLUMN IF NOT EXISTS device_type    VARCHAR(32)  NOT NULL DEFAULT 'POWERBANK' COMMENT '设备类型，硬过滤（修缺陷 1）',
  ADD COLUMN IF NOT EXISTS vendor_code    VARCHAR(32)      NULL COMMENT '厂商过滤；空=不限',
  ADD COLUMN IF NOT EXISTS model          VARCHAR(64)      NULL COMMENT '型号过滤；空=不限',
  ADD COLUMN IF NOT EXISTS brand_no       VARCHAR(36)      NULL COMMENT '消费者品牌过滤；空=不限（待 B1 品牌落地）',
  ADD COLUMN IF NOT EXISTS priority       INT          NOT NULL DEFAULT 0 COMMENT '同层同范围并列时降序裁决',
  ADD COLUMN IF NOT EXISTS effective_from DATETIME(3)      NULL COMMENT '生效起；空=立即',
  ADD COLUMN IF NOT EXISTS effective_to   DATETIME(3)      NULL COMMENT '生效止；空=长期';

-- scope_type 取值域扩为八档（DEVICE/LOCATION/SITE/VENUE/AGENT/SCENE/REGION/ALL）。
-- 只改注释不加 CHECK：MariaDB 的 CHECK 在这张表上会让「先插入后改值域」的迁移变难回退，
-- 合法值由服务层枚举把关（与本库其它枚举列一致）。
ALTER TABLE price_plan_scope
  MODIFY COLUMN scope_type VARCHAR(16) NOT NULL
  COMMENT '层：DEVICE/LOCATION/SITE/VENUE/AGENT/SCENE/REGION/ALL，越靠前越具体';

-- ---------- 二、price_rule 迁入（ADR-028 §落地 第 2 步）----------
-- INSERT IGNORE：新 UK 会挡住「同一范围两个方案」。被挡掉的是**旧数据本来就有的冲突**，
-- 迁移不替业务做选择；迁完由下面的核对查询暴露差额，人工决定留哪个。
INSERT IGNORE INTO price_plan_scope (tenant_id, plan_no, scope_type, scope_ref, device_type, priority)
SELECT COALESCE(tenant_id, 'MAIN'), plan_no, 'SITE', site_no, 'POWERBANK', COALESCE(priority, 0)
  FROM price_rule
 WHERE site_no IS NOT NULL AND site_no <> '';

INSERT IGNORE INTO price_plan_scope (tenant_id, plan_no, scope_type, scope_ref, device_type, priority)
SELECT COALESCE(tenant_id, 'MAIN'), plan_no, 'SCENE', scene_type, 'POWERBANK', COALESCE(priority, 0)
  FROM price_rule
 WHERE (site_no IS NULL OR site_no = '') AND scene_type IS NOT NULL AND scene_type <> '';

-- 换 UK：先删旧的再建新的。新 UK 不含 plan_no —— 见文件头「为什么 UK 要换」。
ALTER TABLE price_plan_scope DROP INDEX IF EXISTS uk_plan_scope;
ALTER TABLE price_plan_scope
  ADD UNIQUE KEY IF NOT EXISTS uk_scope_target (device_type, scope_type, scope_ref, vendor_code, model, brand_no);
ALTER TABLE price_plan_scope ADD KEY IF NOT EXISTS idx_pscope_plan (plan_no);

-- 退役：改名不 DROP。别的环境的行数无法在这里确认，改名保住数据、随时可改回来，
-- 而它已经从活跃 schema 里消失（与 V45 处置 dev_alert 同一原则）。
RENAME TABLE IF EXISTS price_rule TO price_rule_deprecated_v1;

-- ---------- 三、price_schedule 结构化列（修缺陷 5）----------
-- `period` 存的是**展示串**（`周六-周日 18:00-22:00`、`每天`）。后端拿它判倍率就得复刻
-- 前端那个按中文标签解析的 parser —— 而界面还有英文与阿语。用展示串做判断，
-- 与本项目栽过的「按名字连表」是同一类错。故加结构化列，`period` 降级为纯展示。
ALTER TABLE price_schedule
  ADD COLUMN IF NOT EXISTS days      VARCHAR(16) NULL COMMENT '生效星期 CSV，1=周一…7=周日；空=每天',
  ADD COLUMN IF NOT EXISTS time_from CHAR(5)     NULL COMMENT 'HH:mm；与 time_to 同时为空=全天',
  ADD COLUMN IF NOT EXISTS time_to   CHAR(5)     NULL COMMENT 'HH:mm；可跨零点（22:00-06:00 合法）',
  ADD COLUMN IF NOT EXISTS expr      VARCHAR(64) NULL COMMENT '节假日等日历表达式；本期不参与计算，原样保留';

-- 存量 `period` **刻意不回填**：中文串解析不可靠，宁可让存量行暂不生效，
-- 也不要猜错倍率去多收钱。运营在界面上重存一次即可带出结构化值。
