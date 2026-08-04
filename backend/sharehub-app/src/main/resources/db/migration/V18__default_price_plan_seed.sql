-- 默认计价方案种子（M2 下半场：把计费入口从硬编码切到引擎）
--
-- 为什么必须有这个种子：`PriceResolver` 匹配不到方案时**抛异常拒绝结算**，
-- 而不是返回空规格按 0 收费 —— 静默免单无法被发现（本项目栽过一次同型错误：
-- durationMinutes 的 catch→return 0 让每一单都免费且无任何报错）。
-- 拒绝结算是对的，但前提是环境里得有一个方案。
--
-- **参数必须与旧硬编码 Math.min(30, ceil(min/30) * 3) 完全一致**：
-- 切换计费入口不应该改变任何一单的金额。PriceEngineTest 已对 0–2000 分钟逐分
-- 比对过引擎与旧逻辑的等价性，这里保证喂进引擎的参数也等价。

SET NAMES utf8mb4;

INSERT INTO price_plan
  (plan_no, tenant_id, name, free_minutes, unit_minutes, unit_price, cap_daily,
   currency, scope, status, device_type)
VALUES
  ('PP-DEFAULT-PB', 'MAIN', '充电宝默认计费', 0, 30, 3.00, 30.00,
   'AED', 'ALL', 'ENABLED', 'POWERBANK')
ON DUPLICATE KEY UPDATE plan_no = plan_no;

-- 展开为费用项：每 30 分钟计一次，向上取整，日封顶 30
INSERT INTO price_plan_item
  (item_no, tenant_id, plan_no, item_type, metering, free_qty, unit_qty,
   cap_daily, rounding, sort, status)
VALUES
  ('PI-DEFAULT-PB', 'MAIN', 'PP-DEFAULT-PB', 'TIME_FEE', 'MINUTE', 0, 30,
   30.00, 'CEIL', 1, 'ENABLED')
ON DUPLICATE KEY UPDATE item_no = item_no;

-- 单段无上限阶梯 = 无阶梯。3 元/步。
INSERT INTO price_ladder
  (ladder_no, tenant_id, item_no, seq, from_qty, to_qty, unit_price)
VALUES
  ('PL-DEFAULT-PB', 'MAIN', 'PI-DEFAULT-PB', 1, 0, NULL, 3.0000)
ON DUPLICATE KEY UPDATE ladder_no = ladder_no;
