// 预约调价 mock（清单 OM-S4；后端需新表 price_adjustment + 定时任务）。
//
// **到点执行怎么模拟**：不起定时器，改为「惰性执行」——每次读调价列表或保存时，
// 先把已到时间的调价执行掉。时间通过参数注入（tick(now)），单测可以拨时钟验证。
// 幂等：以调价单号 + 状态判断，重复 tick 不会重复改价（服务重启后补执行也走同一条路）。
import type { PriceAdjustment, PriceAdjustPatch, PricePlan, PageQuery } from "../../types";
import { ADJUSTABLE, validateAdjustment } from "../../pricing-rules";
import { paginate, kwHit, upsert, nextNo } from "./helpers";
import { pricePlans } from "./pricing";

const iso = (ms: number) => new Date(Date.now() + ms).toISOString();
const DAY = 86400_000;

export const priceAdjustments: PriceAdjustment[] = [
  {
    adjustNo: "PA001", planNo: "PP002", planName: "机场高价", name: "国庆假期上调单价",
    patch: { unitPrice: 6 }, beforeSnapshot: null,
    effectiveAt: iso(7 * DAY), revertAt: iso(14 * DAY), reason: "国庆客流高峰，机场点位需求集中",
    status: "SCHEDULED", appliedAt: null, revertedAt: null, failReason: null,
    createdBy: "运营中心", createdAt: iso(-2 * DAY),
  },
  {
    adjustNo: "PA002", planNo: "PP003", planName: "商场优惠", name: "开学季免费时长加码",
    patch: { freeMinutes: 20 }, beforeSnapshot: { freeMinutes: 10 },
    effectiveAt: iso(-3 * DAY), revertAt: iso(10 * DAY), reason: "开学季拉新，延长免费时长",
    status: "APPLIED", appliedAt: iso(-3 * DAY), revertedAt: null, failReason: null,
    createdBy: "增长组", createdAt: iso(-9 * DAY),
  },
  {
    adjustNo: "PA003", planNo: "PP001", planName: "标准（默认）", name: "斋月降价（已恢复）",
    patch: { unitPrice: 2 }, beforeSnapshot: { unitPrice: 3 },
    effectiveAt: iso(-40 * DAY), revertAt: iso(-10 * DAY), reason: "斋月促销",
    status: "REVERTED", appliedAt: iso(-40 * DAY), revertedAt: iso(-10 * DAY), failReason: null,
    createdBy: "运营中心", createdAt: iso(-45 * DAY),
  },
];

// 种子里的 PA002 是「已生效」状态，那它对方案的改动就该已经落在方案上，
// 否则页面会出现「调价说免费时长改成 20 了，方案却还是 10」这种自相矛盾。
for (const a of priceAdjustments) {
  if (a.status !== "APPLIED") continue;
  const plan = pricePlans.find((p) => p.planNo === a.planNo);
  if (plan) for (const [k, v] of Object.entries(a.patch)) (plan as unknown as Record<string, number>)[k] = v as number;
}

const planOf = (planNo: string) => pricePlans.find((p) => p.planNo === planNo);

/** 取方案当前值里、调价涉及到的那几个字段，作为快照。 */
function snapshotOf(plan: PricePlan, patch: PriceAdjustPatch): PriceAdjustPatch {
  const snap: PriceAdjustPatch = {};
  for (const f of ADJUSTABLE) if (patch[f.key] != null) snap[f.key] = plan[f.key];
  return snap;
}

const applyPatch = (plan: PricePlan, patch: PriceAdjustPatch) => {
  for (const f of ADJUSTABLE) if (patch[f.key] != null) (plan[f.key] as number) = patch[f.key]!;
};

/** 方案当前值是否仍与调价写入的值一致（不一致 = 调价期间有人手工改过）。 */
const stillMatches = (plan: PricePlan, patch: PriceAdjustPatch) =>
  ADJUSTABLE.every((f) => patch[f.key] == null || plan[f.key] === patch[f.key]);

/**
 * 惰性执行：把到点的调价执行掉 / 到期的恢复掉。幂等。
 * @returns 本次实际发生变化的调价单号（供单测断言）
 */
export function tickAdjustments(now: Date = new Date()): string[] {
  const touched: string[] = [];
  const t = now.getTime();
  for (const a of priceAdjustments) {
    const plan = planOf(a.planNo);
    if (a.status === "SCHEDULED" && new Date(a.effectiveAt).getTime() <= t) {
      if (!plan || plan.archivedAt || plan.status !== "ACTIVE") {
        a.status = "CANCELLED";
        a.failReason = "目标方案已停用或归档，调价自动撤销";
      } else {
        a.beforeSnapshot = snapshotOf(plan, a.patch);
        applyPatch(plan, a.patch);
        a.status = "APPLIED";
        a.appliedAt = now.toISOString();
      }
      touched.push(a.adjustNo);
      // 不 continue：错过多次 tick 时（服务停过），同一次里要把「生效」和「到期恢复」一起补上
    }
    if (a.status === "APPLIED" && a.revertAt && new Date(a.revertAt).getTime() <= t) {
      if (!plan) {
        a.status = "FAILED";
        a.failReason = "目标方案不存在，无法恢复原价";
      } else if (!stillMatches(plan, a.patch)) {
        // 调价期间被人工改过：不覆盖人工修改，转失败让人来判断
        a.status = "FAILED";
        a.failReason = "方案在调价期间被人工修改过，未自动恢复，请人工确认后处理";
      } else {
        applyPatch(plan, a.beforeSnapshot ?? {});
        a.status = "REVERTED";
        a.revertedAt = now.toISOString();
      }
      touched.push(a.adjustNo);
    }
  }
  return touched;
}

export function listPriceAdjustments(q: PageQuery & { planNo?: string; status?: string } = {}) {
  tickAdjustments();
  const rows = priceAdjustments
    .filter((a) => (!q.planNo || a.planNo === q.planNo) && (!q.status || a.status === q.status))
    .sort((a, b) => b.effectiveAt.localeCompare(a.effectiveAt));
  return paginate(rows, q.page, q.size, (x) => kwHit(q.keyword, x.adjustNo, x.name, x.planName, x.reason));
}

export function savePriceAdjustment(x: Partial<PriceAdjustment>): PriceAdjustment {
  tickAdjustments();
  const prev = x.adjustNo ? priceAdjustments.find((a) => a.adjustNo === x.adjustNo) : undefined;
  const plan = planOf(x.planNo ?? prev?.planNo ?? "");
  const merged = { ...(prev ?? {}), ...x } as PriceAdjustment;
  const errors = validateAdjustment(merged, prev, {
    siblings: priceAdjustments.filter((a) => a.planNo === merged.planNo),
    plan, now: new Date(),
  });
  if (errors.length) throw new Error(errors[0]);
  const row: Partial<PriceAdjustment> = {
    ...merged,
    planName: plan?.name ?? merged.planName,
    status: prev?.status ?? "SCHEDULED",
    beforeSnapshot: prev?.beforeSnapshot ?? null,
    appliedAt: prev?.appliedAt ?? null,
    revertedAt: prev?.revertedAt ?? null,
    failReason: prev?.failReason ?? null,
    createdBy: prev?.createdBy ?? "admin",
    createdAt: prev?.createdAt ?? new Date().toISOString(),
  };
  return upsert(priceAdjustments, row, "adjustNo", () => nextNo("PA", priceAdjustments));
}

export function cancelPriceAdjustment(adjustNo: string, reason: string): PriceAdjustment {
  tickAdjustments();
  const a = priceAdjustments.find((x) => x.adjustNo === adjustNo);
  if (!a) throw new Error("调价单不存在");
  if (a.status !== "SCHEDULED") throw new Error(`「${a.status}」状态的调价单不能撤销，只有待生效可以`);
  if (!reason.trim()) throw new Error("请填写撤销原因");
  a.status = "CANCELLED";
  a.failReason = `已撤销：${reason}`;
  return a;
}

/** 提前恢复：不等 revertAt，立即还原快照。 */
export function revertPriceAdjustment(adjustNo: string): PriceAdjustment {
  tickAdjustments();
  const a = priceAdjustments.find((x) => x.adjustNo === adjustNo);
  if (!a) throw new Error("调价单不存在");
  if (a.status !== "APPLIED") throw new Error("只有已生效的调价单可以恢复");
  const plan = planOf(a.planNo);
  if (!plan) throw new Error("目标方案不存在，无法恢复");
  if (!stillMatches(plan, a.patch)) throw new Error("方案在调价期间被人工修改过，请人工确认后处理，避免覆盖别人的改动");
  applyPatch(plan, a.beforeSnapshot ?? {});
  a.status = "REVERTED";
  a.revertedAt = new Date().toISOString();
  return a;
}

/**
 * 重试执行失败的调价单。
 * - 失败在「恢复」这一步（已生效过）：**立刻再恢复一次**——运营点重试就是要现在恢复，
 *   而不是等下一个 revertAt（那个时间点早就过了）
 * - 失败在「生效」这一步：回到待生效，由 tick 按原定时间执行
 */
export function retryPriceAdjustment(adjustNo: string): PriceAdjustment {
  const a = priceAdjustments.find((x) => x.adjustNo === adjustNo);
  if (!a) throw new Error("调价单不存在");
  if (a.status !== "FAILED") throw new Error("只有执行失败的调价单需要重试");
  a.failReason = null;
  if (a.appliedAt) {
    a.status = "APPLIED";
    return revertPriceAdjustment(adjustNo); // 仍被人工改过的话，这里会抛错说明原因
  }
  a.status = "SCHEDULED";
  tickAdjustments();
  return a;
}
