// 计费域：收费方案 pricePlans / 适用范围 planScopes / 时段倍率 pricingSchedules。
import type { PlanScope, PricePlan, PricingSchedule, PageQuery } from "../../types";
import { notFound, fail } from "@/lib/biz-error";
import { p } from "./internal";
import { sites, locations } from "./location";
import { paginate, kwHit, upsert, nextNo, archiveRow, unarchiveRow } from "./helpers";

export const pricePlans: PricePlan[] = [
  { planNo: "PP001", name: "标准（默认）", freeMinutes: 5, unitMinutes: 30, unitPrice: 3, capDaily: 30, buyoutPrice: 60, currency: "AED", scope: "默认", status: "ACTIVE", archivedAt: null },
  { planNo: "PP002", name: "机场高价", freeMinutes: 3, unitMinutes: 30, unitPrice: 5, capDaily: 50, buyoutPrice: 99, currency: "AED", scope: "机场点位", status: "ACTIVE", archivedAt: null },
  { planNo: "PP003", name: "商场优惠", freeMinutes: 10, unitMinutes: 60, unitPrice: 2, capDaily: 20, buyoutPrice: 49, currency: "AED", scope: "商场点位", status: "ACTIVE", archivedAt: null },
  { planNo: "PP004", name: "旧活动价", freeMinutes: 15, unitMinutes: 30, unitPrice: 2, capDaily: 20, buyoutPrice: 40, currency: "AED", scope: "活动", status: "DISABLED", archivedAt: "2026-05-20T09:00:00Z" },
];

/**
 * 适用范围：取价的唯一依据（ADR-028 / V49）。
 *
 * 种子覆盖多个层，页面上各种形态都看得见。注意 **`ALL` 层必须有一条** ——
 * 一条都不命中时取价会抛异常拒绝下单（绝不静默按 0 收费），默认方案就是那个兜底。
 */
export const planScopes: PlanScope[] = [
  { id: 1, planNo: "PP001", scopeType: "ALL", scopeRef: "*", deviceType: "POWERBANK", priority: 0 },
  ...sites.slice(0, 6).map((site, i): PlanScope => ({
    id: 10 + i, planNo: p(["PP002", "PP003"], i), scopeType: "SITE", scopeRef: site.siteNo,
    deviceType: "POWERBANK", priority: (i % 3) + 1,
  })),
  ...locations.slice(0, 2).map((loc, i): PlanScope => ({
    id: 20 + i, planNo: "PP002", scopeType: "LOCATION", scopeRef: loc.locationNo,
    deviceType: "POWERBANK", priority: i + 1,
  })),
  { id: 30, planNo: "PP002", scopeType: "SCENE", scopeRef: "机场", deviceType: "POWERBANK", priority: 2 },
  { id: 31, planNo: "PP003", scopeType: "SCENE", scopeRef: "商场", deviceType: "POWERBANK", priority: 2 },
  // 同一站点 × 指定厂商 —— 比「本站点」多一个过滤器，同层内胜出
  { id: 40, planNo: "PP003", scopeType: "SITE", scopeRef: sites[0].siteNo,
    deviceType: "POWERBANK", vendorCode: "cd-tech", priority: 1 },
];

// 结构化时段（V49）：后端判倍率只读 days/timeFrom/timeTo，period 仅作展示。
// 种子里**故意留了两条只有 expr 的节假日行** —— 后端本期不计算它们，
// 页面上要能看出「存着但不生效」，而不是让人以为配了就有用。
const SCHED_SEED: Array<Pick<PricingSchedule, "name" | "days" | "timeFrom" | "timeTo" | "expr">> = [
  { name: "周末上浮", days: "6,7", timeFrom: null, timeTo: null, expr: null },
  { name: "节假日上浮", days: null, timeFrom: null, timeTo: null, expr: "公共假日" },
  { name: "夜间优惠", days: null, timeFrom: "22:00", timeTo: "06:00", expr: null },
  { name: "斋月特惠", days: null, timeFrom: null, timeTo: null, expr: "斋月全月" },
  { name: "早高峰", days: "1,2,3,4,5", timeFrom: "07:00", timeTo: "09:00", expr: null },
];
const periodOf = (x: (typeof SCHED_SEED)[number]) => {
  const L = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
  const days = (x.days ?? "").split(",").filter(Boolean).map((n) => L[Number(n) - 1]);
  const time = x.timeFrom && x.timeTo ? `${x.timeFrom}-${x.timeTo}` : "";
  return [days.length && days.length < 7 ? days.join("、") : "每天", time, x.expr ?? ""]
    .filter(Boolean).join(" ");
};
export const pricingSchedules: PricingSchedule[] = Array.from({ length: 12 }, (_, i) => {
  const seed = p(SCHED_SEED, i);
  return {
    ruleNo: `PS${500 + i}`, ...seed, period: periodOf(seed),
    multiplier: Number((0.8 + (i % 5) * 0.15).toFixed(2)), active: i % 6 !== 0,
  };
});

export const listPlanScopes = (planNo: string) => planScopes.filter((x) => x.planNo === planNo);
export const listPricingSchedules = (q: PageQuery = {}) => paginate(pricingSchedules, q.page, q.size, (x) => kwHit(q.keyword, x.ruleNo, x.name, x.period));

export const savePricePlan = (x: Partial<PricePlan>) => upsert(pricePlans, x, "planNo", () => nextNo("PP", pricePlans));

/**
 * 适用范围落库。
 *
 * **同一范围只能有一个方案** —— 这条唯一性（后端 `uk_scope_target`）才是「取价必然唯一」的来源。
 * 冲突在这里就要拦住并说清是谁占着：让两个方案在库里并存、由 priority 去猜，
 * 正是这次重写要消灭的那种「配了也不知道生效哪条」。
 */
export const savePlanScope = (planNo: string, x: Partial<PlanScope>) => {
  const level = x.scopeType ?? "ALL";
  const ref = level === "ALL" ? "*" : (x.scopeRef ?? "").trim();
  if (!ref) fail(`${level} 层必须指明具体编号`, `${level} scope requires a reference`);
  const deviceType = (x.deviceType ?? "").trim();
  // 设备类型是硬过滤，缺了就会出现「站点规则把充电宝价给了按摩椅」
  if (!deviceType) fail("适用范围必须指明设备类型", "Device type is required");

  /*
   * 空串与 null 必须归一后再比。
   *
   * 表单里「不限」是一个 value="" 的 option，送过来是 `""`；库里那行是 `null`。
   * 不归一的话 `"" !== null`，两行被当成**不同的范围**双双落库 ——
   * 唯一性形同虚设，取价又回到「靠优先级猜」。后端 `upsertScope` 用 trim() 做了同样的事，
   * 两侧必须一致，否则 mock 下放行、真实后端拒绝。
   */
  const norm = (v: string | null | undefined) => (v == null || v.trim() === "" ? null : v.trim());
  const same = (a: Partial<PlanScope>) =>
    a.deviceType === deviceType && a.scopeType === level && a.scopeRef === ref
    && norm(a.vendorCode) === norm(x.vendorCode)
    && norm(a.model) === norm(x.model)
    && norm(a.brandNo) === norm(x.brandNo);
  const clash = planScopes.find((a) => same(a) && a.id !== x.id);
  if (clash) {
    fail(`这个范围已经由方案 ${clash.planNo} 占用。同一范围只能有一个方案，否则取价要靠优先级猜。`,
      `This scope is already taken by plan ${clash.planNo}.`);
  }

  const row: PlanScope = {
    id: x.id ?? Math.max(0, ...planScopes.map((a) => a.id ?? 0)) + 1,
    planNo, scopeType: level, scopeRef: ref, deviceType,
    vendorCode: norm(x.vendorCode), model: norm(x.model), brandNo: norm(x.brandNo),
    priority: x.priority ?? 0,
    effectiveFrom: x.effectiveFrom ?? null, effectiveTo: x.effectiveTo ?? null,
  };
  const at = planScopes.findIndex((a) => a.id === row.id);
  if (at >= 0) planScopes[at] = row; else planScopes.push(row);
  return row;
};

export const removePlanScope = (planNo: string, id: number) => {
  const at = planScopes.findIndex((a) => a.id === id && a.planNo === planNo);
  if (at < 0) notFound("适用范围", "Plan scope", String(id));
  planScopes.splice(at, 1);
  return { ok: true };
};

/**
 * 时段价落库：`period` 必须非空，且若是可解析的结构化表达式，则**归一化后再存**
 * （「周六 、 周日 18:00-22:00」这类手输空格统一成一种写法，避免同一时段存出多种字面）。
 */
/**
 * 时段倍率落库。校验的是**结构化字段**，不是展示串 `period`。
 *
 * 「只填一端的时刻」在这里就拦住：后端遇到它会判不命中（宁可不加倍也不猜），
 * 但那样运营会看到一条存着却永远不生效的规则，还不知道为什么 —— 所以入口就要说清楚。
 */
export const savePricingSchedule = (x: Partial<PricingSchedule>) => {
  const from = (x.timeFrom ?? "").trim();
  const to = (x.timeTo ?? "").trim();
  if (!!from !== !!to) {
    fail("开始与结束时刻要么都填、要么都留空（都留空 = 全天）",
      "Fill both start and end time, or leave both empty (empty = all day)");
  }
  const hasRange = !!(x.days ?? "").trim() || (!!from && !!to);
  if (!hasRange && !(x.expr ?? "").trim()) {
    fail("时段不能为空：请选星期/时刻，或填节假日表达式",
      "Time window is required: pick weekdays/hours, or enter a holiday expression");
  }
  return upsert(pricingSchedules, x, "ruleNo", () => nextNo("PS", pricingSchedules));
};

// —— G1 软删除：计费模板 ——
export const archivePricePlan = (no: string) => archiveRow(pricePlans, "planNo", no);
export const unarchivePricePlan = (no: string) => unarchiveRow(pricePlans, "planNo", no);
