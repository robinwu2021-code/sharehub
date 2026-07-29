// 覆盖范围：计费定价 —— 价格方案、差异化定价规则、分时定价规则。
import type { PageQ } from "../query";
import type { PageResult, PricePlan, PricingDiff, PricingSchedule } from "../../types";

export interface PricingApi {
  listPricePlans(q?: PageQ): Promise<PageResult<PricePlan>>;

  // === 定价扩展 tab ===
  listPricingDiffs(q?: PageQ): Promise<PageResult<PricingDiff>>;
  listPricingSchedules(q?: PageQ): Promise<PageResult<PricingSchedule>>;
  savePricePlan(x: Partial<PricePlan> & { planNo?: string }): Promise<PricePlan>;
  savePricingDiff(x: Partial<PricingDiff> & { ruleNo?: string }): Promise<PricingDiff>;
  savePricingSchedule(x: Partial<PricingSchedule> & { ruleNo?: string }): Promise<PricingSchedule>;
}
