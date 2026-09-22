// 计费域：计费模板 pricePlans / 场景差异化定价 pricingDiffs / 时段策略 pricingSchedules。
import type { PricePlan, PricingDiff, PricingSchedule, PageQuery } from "../../types";
import { parsePeriod, formatPeriod } from "../../types";
import { p } from "./internal";
import { sites, locations } from "./location";
import { paginate, kwHit, upsert, nextNo, archiveRow, unarchiveRow } from "./helpers";

export const pricePlans: PricePlan[] = [
  { planNo: "PP001", name: "标准（默认）", freeMinutes: 5, unitMinutes: 30, unitPrice: 3, capDaily: 30, buyoutPrice: 60, currency: "AED", scope: "默认", status: "ACTIVE", archivedAt: null },
  { planNo: "PP002", name: "机场高价", freeMinutes: 3, unitMinutes: 30, unitPrice: 5, capDaily: 50, buyoutPrice: 99, currency: "AED", scope: "机场点位", status: "ACTIVE", archivedAt: null },
  { planNo: "PP003", name: "商场优惠", freeMinutes: 10, unitMinutes: 60, unitPrice: 2, capDaily: 20, buyoutPrice: 49, currency: "AED", scope: "商场点位", status: "ACTIVE", archivedAt: null },
  { planNo: "PP004", name: "旧活动价", freeMinutes: 15, unitMinutes: 30, unitPrice: 2, capDaily: 20, buyoutPrice: 40, currency: "AED", scope: "活动", status: "DISABLED", archivedAt: "2026-05-20T09:00:00Z" },
];

// 规则挂在**真实实体**上（S6 + 三维扩展）：冗余列一律由 matchRef 按维度反查，不独立取模——
// 否则会造出「场景=机场、点位=商场」这种自相矛盾、且目标压根不存在的规则。
// 种子覆盖三个维度（12 SITE + 3 LOCATION + 2 SCENE），页面上三种形态都看得见。
export const pricingDiffs: PricingDiff[] = [
  ...Array.from({ length: 12 }, (_, i): PricingDiff => {
    const site = sites[i % sites.length];
    return {
      ruleNo: `PD${400 + i}`, dimension: "SITE", matchRef: site.siteNo,
      siteNo: site.siteNo, scene: site.sceneType, locationName: site.name,
      freeMinutes: p([3, 5, 10], i), unitPrice: p([2, 3, 5], i), capDaily: p([20, 30, 50], i),
      priority: (i % 3) + 1, currency: "AED",
    };
  }),
  ...Array.from({ length: 3 }, (_, i): PricingDiff => {
    const loc = locations[i * 7]; // 隔位取，避免全落在同一站点
    const site = sites.find((s) => s.siteNo === loc.siteNo)!;
    return {
      ruleNo: `PD${412 + i}`, dimension: "LOCATION", matchRef: loc.locationNo,
      siteNo: loc.siteNo, scene: site.sceneType, locationName: loc.name,
      freeMinutes: p([5, 10], i), unitPrice: p([3, 4], i), capDaily: p([25, 35], i),
      priority: i + 1, currency: "AED",
    };
  }),
  // SCENE 维不落到具体站点/点位，siteNo/locationName 置空串（integrity 按维度分别断言）
  ...["机场", "商场"].map((scene, i): PricingDiff => ({
    ruleNo: `PD${415 + i}`, dimension: "SCENE", matchRef: scene,
    siteNo: "", scene, locationName: "",
    freeMinutes: p([3, 10], i), unitPrice: p([5, 2], i), capDaily: p([50, 20], i),
    priority: i + 2, currency: "AED",
  })),
];
export const pricingSchedules: PricingSchedule[] = Array.from({ length: 12 }, (_, i) => ({
  ruleNo: `PS${500 + i}`, name: p(["周末上浮", "节假日上浮", "夜间优惠", "斋月特惠", "早高峰"], i),
  period: p(["周六-周日", "公共假日", "22:00-06:00", "斋月全月", "07:00-09:00"], i),
  multiplier: Number((0.8 + (i % 5) * 0.15).toFixed(2)), active: i % 6 !== 0,
}));

export const listPricingDiffs = (q: PageQuery = {}) => paginate(pricingDiffs, q.page, q.size, (x) => kwHit(q.keyword, x.ruleNo, x.matchRef, x.siteNo, x.scene, x.locationName));
export const listPricingSchedules = (q: PageQuery = {}) => paginate(pricingSchedules, q.page, q.size, (x) => kwHit(q.keyword, x.ruleNo, x.name, x.period));

export const savePricePlan = (x: Partial<PricePlan>) => upsert(pricePlans, x, "planNo", () => nextNo("PP", pricePlans));

/**
 * 差异化规则落库：**目标必须真实存在**（按维度查各自的表），冗余列由服务端反查覆盖。
 * 校验放这一层而不是页面里——页面下拉只是便利，接口收到一个乱填的 matchRef 也不能进库，
 * 否则「规则指向不存在的目标」这条缺陷换个入口就复发。
 * 兼容旧调用形状：不传 dimension 视为 SITE，此时 `siteNo` 充当 matchRef（S6 时期的契约）。
 */
export const savePricingDiff = (x: Partial<PricingDiff>) => {
  const dimension = x.dimension ?? "SITE";
  const matchRef = (x.matchRef ?? (dimension === "SITE" ? x.siteNo : "") ?? "").trim();
  let derived: Pick<PricingDiff, "siteNo" | "scene" | "locationName">;
  if (dimension === "SITE") {
    const site = sites.find((s) => s.siteNo === matchRef);
    if (!site) throw new Error(`站点不存在：${matchRef || "(未选择)"}`);
    derived = { siteNo: site.siteNo, scene: site.sceneType, locationName: site.name };
  } else if (dimension === "LOCATION") {
    const loc = locations.find((l) => l.locationNo === matchRef);
    if (!loc) throw new Error(`点位不存在：${matchRef || "(未选择)"}`);
    const site = sites.find((s) => s.siteNo === loc.siteNo);
    derived = { siteNo: loc.siteNo, scene: site?.sceneType ?? "", locationName: loc.name };
  } else {
    // SCENE：场景值必须在站点表里真实出现过——挂在没有任何站点的场景上，规则永远命中不到
    if (!sites.some((s) => s.sceneType === matchRef)) {
      throw new Error(`场景不存在（没有任何站点属于它）：${matchRef || "(未选择)"}`);
    }
    derived = { siteNo: "", scene: matchRef, locationName: "" };
  }
  return upsert(pricingDiffs, { ...x, dimension, matchRef, ...derived }, "ruleNo", () => nextNo("PD", pricingDiffs));
};

/**
 * 时段价落库：`period` 必须非空，且若是可解析的结构化表达式，则**归一化后再存**
 * （「周六 、 周日 18:00-22:00」这类手输空格统一成一种写法，避免同一时段存出多种字面）。
 */
export const savePricingSchedule = (x: Partial<PricingSchedule>) => {
  const period = (x.period ?? "").trim();
  if (!period) throw new Error("时段不能为空：请选星期/时刻，或填自定义表达式");
  const spec = parsePeriod(period);
  return upsert(pricingSchedules, { ...x, period: spec.kind === "RANGE" ? formatPeriod(spec) : period }, "ruleNo", () => nextNo("PS", pricingSchedules));
};

// —— G1 软删除：计费模板 ——
export const archivePricePlan = (no: string) => archiveRow(pricePlans, "planNo", no);
export const unarchivePricePlan = (no: string) => unarchiveRow(pricePlans, "planNo", no);
