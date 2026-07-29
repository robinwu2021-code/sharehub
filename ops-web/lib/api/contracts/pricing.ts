// 覆盖范围：计费定价 —— 价格方案、差异化定价规则、分时定价规则。
import type { PageQ, ArchiveQ } from "../query";
import type { PageResult, PricePlan, PricingDiff, PricingSchedule } from "../../types";

export interface PricingApi {
  listPricePlans(q?: ArchiveQ): Promise<PageResult<PricePlan>>;

  // === 定价扩展 tab ===
  listPricingDiffs(q?: PageQ): Promise<PageResult<PricingDiff>>;
  listPricingSchedules(q?: PageQ): Promise<PageResult<PricingSchedule>>;
  savePricePlan(x: Partial<PricePlan> & { planNo?: string }): Promise<PricePlan>;
  savePricingDiff(x: Partial<PricingDiff> & { ruleNo?: string }): Promise<PricingDiff>;
  savePricingSchedule(x: Partial<PricingSchedule> & { ruleNo?: string }): Promise<PricingSchedule>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  archivePricePlan(planNo: string): Promise<PricePlan>;
  unarchivePricePlan(planNo: string): Promise<PricePlan>;
}
