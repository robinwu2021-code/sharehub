-- ============================================================
-- powerbank · 收费方案状态取值归一（ACTIVE / DISABLED）
--
-- 背景：同一列在三处用了两套词。
--   · `price_plan` 的 DDL 默认值是 'ACTIVE'；
--   · `PricePlanServiceImpl` 新建时写 'ACTIVE'；运营端契约也是 ACTIVE / DISABLED；
--   · 而**计价引擎 `PriceResolver` 只选 'ENABLED'**。
--
-- 后果：运营在界面上新建一个收费方案，状态显示「启用」、列表里好端端列着，
--       但计价引擎永远选不中它 —— 订单一律按兜底价计费，而且没有任何报错。
--       今天没出事，只是因为种子里那一行恰好写的是 'ENABLED'。
--
-- 本脚本把存量归一到 'ACTIVE'，代码侧同步改 PriceResolver 的过滤值。
-- 只动 `price_plan`：`price_plan_item` 有自己的一套（DDL 默认就是 'ENABLED'），
-- 两张表不共享这个值域，一起改反而会制造新的不一致。
-- ============================================================
SET NAMES utf8mb4;

UPDATE price_plan SET status = 'ACTIVE' WHERE status = 'ENABLED';
