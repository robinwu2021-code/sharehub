// 覆盖范围：计费域（trade）——计费模板、差异化定价规则、时段调价。
//
// ⚠️ 计费字段命名规范（2026-07-29 统一，台账 T2）。曾经四处各起一套名
// （PricePlan / PricingDiff / BillingDefaultRule / TenantConfig），后端 DTO 会被直接传染，故收敛为：
//   freeMinutes  免费时长（分）   unitMinutes 计费单位（分）   unitPrice 单位价
//   capDaily     日封顶           buyoutPrice 买断价（原叫 capTotal，但它就是行业说的"买断"）
// **新增任何计费相关字段一律沿用这套名，勿再造同义词。**

import type { Archivable } from "./common";

export interface PricePlan extends Archivable {
  planNo: string;
  name: string;
  freeMinutes: number;
  unitMinutes: number;
  unitPrice: number;
  capDaily: number;
  buyoutPrice: number; // 买断价
  currency: string;
  scope: string; // 默认/点位/场景
  status: "ACTIVE" | "DISABLED";
}

// —— 计费 · 待建功能补全（trade 域）——
export interface PricingDiff {
  ruleNo: string;
  scene: string;
  locationName: string;
  freeMinutes: number;
  unitPrice: number;
  capDaily: number;
  priority: number;
  currency: string;
}
export interface PricingSchedule {
  ruleNo: string;
  name: string;
  period: string;
  multiplier: number;
  active: boolean;
}
