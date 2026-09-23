// 覆盖范围：计费定价 —— 收费方案、适用范围（取价唯一依据）、分时倍率。
import type { PageQ, ArchiveQ } from "../query";
import type { PageResult, PlanScope, PricePlan, PricingSchedule } from "../../types";

export interface PricingApi {
  listPricePlans(q?: ArchiveQ): Promise<PageResult<PricePlan>>;
  listPricingSchedules(q?: PageQ): Promise<PageResult<PricingSchedule>>;
  savePricePlan(x: Partial<PricePlan> & { planNo?: string }): Promise<PricePlan>;
  savePricingSchedule(x: Partial<PricingSchedule> & { ruleNo?: string }): Promise<PricingSchedule>;

  /*
   * 适用范围（ADR-028）。2026-09-23 之前**前端没有任何写入口** ——
   * 界面上的「适用范围」只是一个自由文本描述框，真正被取价引擎读的那张表只有种子能写。
   * 「配了不生效」的另一半是「压根没法配」。
   */
  listPlanScopes(planNo: string): Promise<PlanScope[]>;
  savePlanScope(planNo: string, x: Partial<PlanScope>): Promise<PlanScope>;
  /** 契约禁止 delete*，用 remove。 */
  removePlanScope(planNo: string, id: number): Promise<{ ok: boolean }>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  archivePricePlan(planNo: string): Promise<PricePlan>;
  unarchivePricePlan(planNo: string): Promise<PricePlan>;
}
