-- wo_order.source 的词表漏了 PLAN（巡检计划生成的工单）。
--
-- 三处都已经认这个值，只有列注释没跟上：
--   · 写入校验 WoOpsServiceImpl.SOURCES = {ALERT, USER, VENUE, MANUAL, PLAN}；
--   · 运营端 work-orders/page.tsx 的来源映射有 `PLAN: "巡检计划"`；
--   · InspectionPlanServiceImpl 生成巡检工单时就是写 source=PLAN。
-- 而**列注释是本仓库「两端对不上时以哪边为准」的裁定依据**（见
-- StoredValueInVocabularyTest 的类注释）——依据本身漏了一档，就没有裁判。
--
-- 为什么一直没被发现：StoredValueInVocabularyTest 是**数据驱动**的 ——
-- 它只能看见库里已经存在的值。`wo_order.source='PLAN'` 需要有人真的跑一次
-- 巡检计划生成才会出现，而在 2026-09-25 之前测试库里一行都没有。
-- 这次是 JobCatalogTest 触发 inspection-plan-run 把这条路径跑通，才让它显形。
-- ⇒ 这条卡口的边界要记住：**没被跑过的代码路径，它看不见**。
ALTER TABLE wo_order
  MODIFY COLUMN source VARCHAR(16) NOT NULL
  COMMENT 'ALERT/USER/VENUE/MANUAL/PLAN';
