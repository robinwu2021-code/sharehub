// 覆盖范围：计费域（trade）——计费模板、差异化定价规则、时段调价。

export interface PricePlan {
  planNo: string;
  name: string;
  freeMinutes: number;
  unitMinutes: number;
  unitPrice: number;
  capDaily: number;
  capTotal: number; // 买断价
  currency: string;
  scope: string; // 默认/点位/场景
  status: "ACTIVE" | "DISABLED";
}

// —— 计费 · 待建功能补全（trade 域）——
export interface PricingDiff {
  ruleNo: string;
  scene: string;
  locationName: string;
  freeMins: number;
  unitPrice: number;
  dayCap: number;
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
