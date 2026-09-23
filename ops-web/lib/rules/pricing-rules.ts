// 运营管理 · 收费方案与预约调价的规则（清单 OM-S3 / OM-S4）。
//
// 与 operation-rules 一样：**页面校验与 mock 写入共用这一份**，单测钉住。
// 试算口径必须与真实计费一致——页面上显示「这样收费」，实际扣的却是另一个数，比没有试算更糟。
import type { PricePlan, PriceAdjustment, PriceAdjustPatch } from "../types";
import { money } from "../utils";

// ——— 计费摘要与试算 ————————————————————————————————————————

/** 一句话说清这个方案怎么收钱，列表里直接可核对，不用点进去。 */
export function planSummary(p: Pick<PricePlan, "freeMinutes" | "unitMinutes" | "unitPrice" | "capDaily" | "buyoutPrice" | "currency">): string {
  const parts: string[] = [];
  if (p.freeMinutes > 0) parts.push(`前 ${p.freeMinutes} 分钟免费`);
  parts.push(`每 ${p.unitMinutes} 分钟 ${money(p.unitPrice, p.currency)}`);
  if (p.capDaily > 0) parts.push(`日封顶 ${money(p.capDaily, p.currency)}`);
  if (p.buyoutPrice > 0) parts.push(`买断 ${money(p.buyoutPrice, p.currency)}`);
  return parts.join("，");
}

export interface SimulateSegment { label: string; detail: string; amount: number }
export interface SimulateResult {
  segments: SimulateSegment[];
  total: number;
  currency: string;
  /** 是否触发买断（达到买断价后不再计费，设备归用户） */
  buyout: boolean;
}

/**
 * 按时长试算费用。规则（与 TDD-核心业务逻辑的取价链一致）：
 * 免费时长 → 不足一个计费单位按一个算（向上取整）→ 每日封顶 → 总额达买断价即买断。
 *
 * @param minutes  租借总时长（分钟）
 * @param multiplier 时段倍率，默认 1
 */
export function simulate(
  p: Pick<PricePlan, "freeMinutes" | "unitMinutes" | "unitPrice" | "capDaily" | "buyoutPrice" | "currency">,
  minutes: number,
  multiplier = 1,
): SimulateResult {
  const cur = p.currency || "AED";
  const segs: SimulateSegment[] = [];
  const billableMin = Math.max(0, minutes - p.freeMinutes);
  if (p.freeMinutes > 0) {
    segs.push({ label: "免费时长", detail: `前 ${p.freeMinutes} 分钟`, amount: 0 });
  }
  const unit = Math.max(1, p.unitMinutes);
  const units = Math.ceil(billableMin / unit);
  let amount = units * p.unitPrice * multiplier;
  segs.push({
    label: "计费时长",
    detail: `${billableMin} 分钟 ÷ ${unit} 分钟 = ${units} 个计费单位 × ${money(p.unitPrice, cur)}${multiplier !== 1 ? ` × 倍率 ${multiplier}` : ""}`,
    amount: round2(amount),
  });

  // 日封顶按自然天数算：租 3 天最多收 3 个日封顶
  if (p.capDaily > 0) {
    const days = Math.max(1, Math.ceil(minutes / (24 * 60)));
    const cap = p.capDaily * days;
    if (amount > cap) {
      segs.push({ label: "日封顶", detail: `${days} 天 × ${money(p.capDaily, cur)}，超出部分不计`, amount: round2(cap - amount) });
      amount = cap;
    }
  }

  let buyout = false;
  if (p.buyoutPrice > 0 && amount >= p.buyoutPrice) {
    segs.push({ label: "买断", detail: `达到买断价，不再继续计费`, amount: round2(p.buyoutPrice - amount) });
    amount = p.buyoutPrice;
    buyout = true;
  }
  return { segments: segs, total: round2(amount), currency: cur, buyout };
}

const round2 = (n: number) => Math.round(n * 100) / 100;

/** 保存收费方案前的校验。 */
export function validatePricePlan(next: Partial<PricePlan>, prev: PricePlan | undefined, all: PricePlan[]): string[] {
  const e: string[] = [];
  if (!(next.name ?? "").trim()) e.push("请填写方案名称");
  if (all.some((p) => p.name === next.name && p.planNo !== prev?.planNo && !p.archivedAt)) e.push(`方案名称「${next.name}」已存在`);
  const num = (v: unknown) => Number(v ?? 0);
  if (num(next.freeMinutes) < 0) e.push("免费时长不能为负数");
  if (num(next.unitMinutes) <= 0) e.push("计费单位必须大于 0 分钟");
  if (num(next.unitPrice) <= 0) e.push("单价必须大于 0");
  if (num(next.capDaily) < 0) e.push("日封顶不能为负数");
  if (num(next.buyoutPrice) < 0) e.push("买断价不能为负数");
  if (num(next.capDaily) > 0 && num(next.buyoutPrice) > 0 && num(next.buyoutPrice) < num(next.capDaily)) {
    e.push("买断价不能低于日封顶——否则第一天就会直接买断");
  }
  return e;
}

// ——— 预约调价 ————————————————————————————————————————————

/** 调价可改的字段与中文名（顺序即展示顺序）。 */
export const ADJUSTABLE: { key: keyof PriceAdjustPatch; label: string; money?: boolean; unit?: string }[] = [
  { key: "freeMinutes", label: "免费时长", unit: "分钟" },
  { key: "unitMinutes", label: "计费单位", unit: "分钟" },
  { key: "unitPrice", label: "单价", money: true },
  { key: "capDaily", label: "日封顶", money: true },
  { key: "buyoutPrice", label: "买断价", money: true },
];

export const ADJUST_STATUS_LABEL: Record<PriceAdjustment["status"], string> = {
  SCHEDULED: "待生效",
  APPLIED: "已生效",
  REVERTED: "已恢复",
  CANCELLED: "已撤销",
  FAILED: "执行失败",
};

const ADJUST_NEXT: Record<PriceAdjustment["status"], PriceAdjustment["status"][]> = {
  SCHEDULED: ["APPLIED", "CANCELLED", "FAILED"],
  APPLIED: ["REVERTED", "FAILED"],
  REVERTED: [],
  CANCELLED: [],
  FAILED: ["SCHEDULED", "APPLIED"], // 重试回到待生效；恢复失败后重试直接再执行一次恢复
};
export function canTransitAdjust(from: PriceAdjustment["status"], to: PriceAdjustment["status"]): boolean {
  return from === to || ADJUST_NEXT[from].includes(to);
}

/** 调整摘要：只列真正变化的字段，「旧 → 新」。 */
export function adjustSummary(patch: PriceAdjustPatch, plan?: Pick<PricePlan, "freeMinutes" | "unitMinutes" | "unitPrice" | "capDaily" | "buyoutPrice" | "currency">): string {
  const out: string[] = [];
  for (const f of ADJUSTABLE) {
    const to = patch[f.key];
    if (to == null) continue;
    const from = plan?.[f.key];
    const fmt = (v: number | undefined) => v == null ? "—" : f.money ? money(v, plan?.currency) : `${v}${f.unit ?? ""}`;
    if (from != null && from === to) continue;
    out.push(`${f.label} ${fmt(from)} → ${fmt(to)}`);
  }
  return out.join("；") || "（没有任何字段变化）";
}

export interface ValidateAdjustCtx {
  /** 同一方案的其它调价单（判断时间窗重叠） */
  siblings: PriceAdjustment[];
  plan?: PricePlan;
  now: Date;
  /** 生效时间至少要晚于当前多少分钟 */
  minLeadMinutes?: number;
}

export function validateAdjustment(next: Partial<PriceAdjustment>, prev: PriceAdjustment | undefined, ctx: ValidateAdjustCtx): string[] {
  const e: string[] = [];
  const lead = ctx.minLeadMinutes ?? 5;
  if (!(next.name ?? "").trim()) e.push("请填写调价单名称");
  if (!next.planNo) e.push("请选择目标方案");
  if (!(next.reason ?? "").trim()) e.push("请填写调价原因");
  if (ctx.plan && ctx.plan.status !== "ACTIVE") e.push("目标方案已停用或归档，不能为它安排调价");

  if (prev && prev.status !== "SCHEDULED") e.push(`「${ADJUST_STATUS_LABEL[prev.status]}」的调价单不能再修改`);

  const eff = next.effectiveAt ? new Date(next.effectiveAt) : null;
  if (!eff || Number.isNaN(eff.getTime())) e.push("请填写生效时间");
  else if (eff.getTime() < ctx.now.getTime() + lead * 60_000) e.push(`生效时间至少要比现在晚 ${lead} 分钟`);

  if (next.revertAt) {
    const rev = new Date(next.revertAt);
    if (Number.isNaN(rev.getTime())) e.push("恢复时间格式不正确");
    else if (eff && rev.getTime() <= eff.getTime()) e.push("恢复时间必须晚于生效时间");
  }

  const patch = next.patch ?? {};
  const changed = ADJUSTABLE.filter((f) => patch[f.key] != null && (!ctx.plan || patch[f.key] !== ctx.plan[f.key]));
  if (!changed.length) e.push("至少要改动一个字段，否则这次调价没有意义");
  for (const f of ADJUSTABLE) {
    const v = patch[f.key];
    if (v == null) continue;
    if (v < 0) e.push(`${f.label}不能为负数`);
    if (f.key === "unitMinutes" && v <= 0) e.push("计费单位必须大于 0 分钟");
    if (f.key === "unitPrice" && v <= 0) e.push("单价必须大于 0");
  }

  // 同一方案的待生效调价，时间窗不能重叠
  if (eff) {
    const myEnd = next.revertAt ? new Date(next.revertAt).getTime() : eff.getTime();
    for (const s of ctx.siblings) {
      if (s.adjustNo === prev?.adjustNo) continue;
      if (s.status !== "SCHEDULED" && s.status !== "APPLIED") continue;
      const sStart = new Date(s.effectiveAt).getTime();
      const sEnd = s.revertAt ? new Date(s.revertAt).getTime() : sStart;
      if (eff.getTime() <= sEnd && myEnd >= sStart) {
        e.push(`与调价单 ${s.adjustNo}（${ADJUST_STATUS_LABEL[s.status]}）的时间窗重叠，同一方案同时只能有一个调价在途`);
        break;
      }
    }
  }
  return e;
}
