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

/**
 * 取价维度（对齐后端 `PriceRule.dimension`）：SITE=单站 · LOCATION=站内单点 · SCENE=同场景全部站点。
 * 命中多条时按 `priority` 取第一条（数值小者优先，与后端计价约定一致）。
 */
export type PricingDimension = "SITE" | "LOCATION" | "SCENE";
/** 维度展示文案 SSOT：表单下拉、表格徽标、CSV 导出共用这一份，防止三处各叫各的。 */
export const PRICING_DIMENSION_LABEL: Record<PricingDimension, string> = {
  SITE: "站点", LOCATION: "点位", SCENE: "场景",
};

/**
 * 差异化定价规则。
 *
 * S6：定位键从自由文本收敛为真键（原 `locationName` 一个自由文本，站点改名或压根不存在时
 * 规则就悬空了，取价永远命中不到）。本轮再对齐后端 `PriceRule` 的三维形态：
 * **`dimension + matchRef` 是规则真正的定位键**，`siteNo/scene/locationName` 全部降级为
 * **冗余展示列**（服务端按维度反查覆盖，页面不让手填），与 `Site.regionId`/`regionName`
 * 同一套「存 ID、冗余名」写法。
 */
export interface PricingDiff {
  ruleNo: string;
  /** 取价维度（对齐后端 `PriceRule.dimension`）。 */
  dimension: PricingDimension;
  /** 该维度下的匹配值：SITE=`sites.siteNo` · LOCATION=`locations.locationNo` · SCENE=`sceneType` 值（对齐后端 `matchRef`）。 */
  matchRef: string;
  /** 站点号冗余：SITE=matchRef 本身；LOCATION=点位所属站点；SCENE 不落站点 → 空串。 */
  siteNo: string;
  /** 场景冗余：SITE/LOCATION 取所在站点的 `sceneType`；SCENE=matchRef 本身。 */
  scene: string;
  /** 目标展示名冗余：SITE=站点名 · LOCATION=点位名 · SCENE 无实体 → 空串。字段名沿用后端 `PriceRule.locationName`。 */
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
  /** 时段表达式原文。落库仍是一个字符串（后端 `PriceSchedule.period` 只存表达式），结构化只在编辑期。 */
  period: string;
  multiplier: number;
  active: boolean;
}

// ————————————————————————————————————————————————————————————————
// 时段表达式：结构化 ⇄ 字符串（SSOT，页面编辑器与 mock 校验共用）
// ————————————————————————————————————————————————————————————————
//
// S6：活动/时段价的 `period` 从前是纯自由文本，「周六-周日」和「周末」「Sat-Sun」并存，计价端无从解析。
// 但**不能**改成一堆结构化列——后端 `price_schedule.period` 就一列表达式，且现实里存在
// 「公共假日」「斋月全月」这类日历事件，天生不是「星期 + 时刻」能表达的。
// 折中：编辑期结构化（星期多选 + 时刻区间），提交前折叠回同一个 `period` 字符串；
// 表达不了的走 EXPR 原文兜底。**parse ∘ format 必须恒等**（round-trip 测试钉住）。

/** RANGE = 星期 + 时刻区间（可解析）；EXPR = 日历事件等自由表达式（原文照存）。 */
export type PeriodKind = "RANGE" | "EXPR";

/** 时段表达式的结构化形态。`days` 用 CSV 存（ISO 星期 1..7，空 = 每天），直接喂 multiselect 的 csv 模式。 */
export interface PeriodSpec {
  kind: PeriodKind;
  days: string;
  from: string;
  to: string;
  expr: string;
}

/** ISO 星期 1..7 的中文标签（下标 +1 = 星期几）。 */
const WEEKDAY_LABELS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
export const WEEKDAY_OPTIONS = WEEKDAY_LABELS.map((label, i) => ({ value: String(i + 1), label }));
/** 星期留空即「每天」；同时也是「无星期无时刻」时的兜底文案，保证 period 永不为空串。 */
export const PERIOD_ANY_DAY = "每天";
/** HH:mm（24 时制）。跨零点（22:00-06:00）是合法的，不校验 from < to。 */
export const TIME_PATTERN = "^([01][0-9]|2[0-3]):[0-5][0-9]$";

const dayNums = (csv: string) =>
  [...new Set((csv ?? "").split(",").map((s) => Number(s.trim())).filter((n) => n >= 1 && n <= 7))].sort((a, b) => a - b);

/** 星期段文案：连号收成区间（6,7 → 周六-周日），断号用「、」列举，全空 = 每天（返回空串由调用方兜底）。 */
function daysLabel(csv: string): string {
  const ns = dayNums(csv);
  if (ns.length === 0 || ns.length === 7) return "";
  if (ns.length === 1) return WEEKDAY_LABELS[ns[0] - 1];
  const contiguous = ns.every((n, i) => i === 0 || n === ns[i - 1] + 1);
  return contiguous
    ? `${WEEKDAY_LABELS[ns[0] - 1]}-${WEEKDAY_LABELS[ns[ns.length - 1] - 1]}`
    : ns.map((n) => WEEKDAY_LABELS[n - 1]).join("、");
}

const WEEKDAY_TOKEN = "周[一二三四五六日]";
const TIME_RANGE_RE = /([01]\d|2[0-3]):[0-5]\d\s*-\s*([01]\d|2[0-3]):[0-5]\d/;

/** 解析星期段；返回 CSV，无法解析返回 null（调用方据此判定为 EXPR）。 */
function parseDays(text: string): string | null {
  // 空白一律先剥掉：手输的「周六 、 周日」与「周六、周日」是同一件事，不该一个能解析一个不能
  const s = text.replace(/\s+/g, "");
  if (s === "" || s === PERIOD_ANY_DAY) return "";
  const idx = (label: string) => String(WEEKDAY_LABELS.indexOf(label) + 1);
  const range = s.match(new RegExp(`^(${WEEKDAY_TOKEN})\\s*-\\s*(${WEEKDAY_TOKEN})$`));
  if (range) {
    const [a, b] = [Number(idx(range[1])), Number(idx(range[2]))];
    if (a > b) return null; // 「周日-周一」这种跨周写法不猜，交给 EXPR 原样保留
    return Array.from({ length: b - a + 1 }, (_, i) => String(a + i)).join(",");
  }
  if (new RegExp(`^${WEEKDAY_TOKEN}(、${WEEKDAY_TOKEN})*$`).test(s)) {
    return s.split("、").map(idx).join(",");
  }
  return null;
}

/** 字符串 → 结构化。解析不了的一律 EXPR 原文兜底，绝不丢用户已存的表达式。 */
export function parsePeriod(period: string): PeriodSpec {
  const raw = (period ?? "").trim();
  const m = raw.match(TIME_RANGE_RE);
  const rest = m ? raw.replace(m[0], "").trim() : raw;
  const days = parseDays(rest);
  if (days === null) return { kind: "EXPR", days: "", from: "", to: "", expr: raw };
  return { kind: "RANGE", days, from: m ? m[0].split("-")[0].trim() : "", to: m ? m[0].split("-")[1].trim() : "", expr: "" };
}

/** 结构化 → 字符串。落库/展示只认它的输出，页面上的预览也来自这里，避免「看到的」与「存下的」两套。 */
export function formatPeriod(s: PeriodSpec): string {
  if (s.kind === "EXPR") return (s.expr ?? "").trim();
  const time = s.from && s.to ? `${s.from}-${s.to}` : "";
  return [daysLabel(s.days), time].filter(Boolean).join(" ") || PERIOD_ANY_DAY;
}
