// 覆盖范围：计费域（trade）——收费方案、适用范围（取价唯一依据）、时段倍率。
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

// —— 适用范围：取价的唯一依据（ADR-028）——

/**
 * 适用范围的层。**顺序即优先级**，越靠前越具体（与后端 `ScopeLevel` 的声明顺序一一对应）。
 *
 * <p>2026-09-23 取代了原来的 `PricingDimension`（SITE/LOCATION/SCENE 三维）。
 * 那套叫「差异化定价」，与方案上的「适用范围」表达同一件事，而取价引擎**两套都没读到**
 * （下单时站点压根没传进去）。现在只有这一套。
 */
export const SCOPE_LEVELS = [
  "DEVICE", "LOCATION", "SITE", "VENUE", "AGENT", "SCENE", "REGION", "ALL",
] as const;
export type ScopeLevel = (typeof SCOPE_LEVELS)[number];

/** 层的展示文案与它选的是什么 —— 表单下拉、表格徽标共用这一份。 */
export const SCOPE_LEVEL_LABEL: Record<ScopeLevel, string> = {
  DEVICE: "单台设备", LOCATION: "点位", SITE: "站点", VENUE: "场地方",
  AGENT: "代理商", SCENE: "场景", REGION: "区域", ALL: "默认（全部）",
};

/** `ALL` 层的引用值。空串会与「没填」混淆，故用显式的 `*`（与后端 `ScopeLevel.ALL_REF` 一致）。 */
export const SCOPE_ALL_REF = "*";

/**
 * 收费方案的一条适用范围。
 *
 * 厂商 / 型号 / 品牌是**过滤条件不是层**：它们与场所层级正交（快充柜可能出现在任何站点）。
 * 「本站点 × 厂商 X」比「本站点」更具体，同层内胜出。
 */
export interface PlanScope {
  /** 这张表没有业务号（它不是独立对象，只是方案的一条范围），故用 id。新增时为空。 */
  id?: number;
  planNo: string;
  scopeType: ScopeLevel;
  /** 对应层的业务号；`ALL` 层固定为 `*`。 */
  scopeRef: string;
  /** 设备类型硬过滤 —— 缺了就会出现「站点规则把充电宝价给了按摩椅」。 */
  deviceType: string;
  vendorCode?: string | null;
  model?: string | null;
  brandNo?: string | null;
  /** 同层同范围并列时降序裁决。 */
  priority?: number;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
}

export interface PricingSchedule {
  ruleNo: string;
  name: string;
  /**
   * 时段表达式原文 —— **只作展示**。
   *
   * 2026-09-23：后端判倍率改读下面的结构化字段。原先只存这一个中文串，
   * 后端要用它就得复刻前端那个按中文标签解析的 parser，而界面还有英文与阿语 ——
   * 用展示串做判断，与本项目栽过的「按名字连表」是同一类错。
   */
  period: string;
  /** 生效星期 CSV，`1`=周一…`7`=周日；空 = 每天。 */
  days?: string | null;
  /** `HH:mm`；与 `timeTo` 同时为空 = 全天。 */
  timeFrom?: string | null;
  /** `HH:mm`；可跨零点（`22:00-06:00` 合法）。 */
  timeTo?: string | null;
  /** 节假日等日历表达式；后端本期不参与计算，原样保留。 */
  expr?: string | null;
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
