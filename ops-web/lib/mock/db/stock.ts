// 库存流水与盘点（S4）mock：演示按独立进销存引擎（盘点可用）。
// 流水取 mock 履历里进出仓的行（同后端本地引擎口径）；盘点账面 = 该仓在库、未出库的柜机。
import type {
  InventoryEngine, StockStage, StockStageCode, StageItem, StageItems, IntakeItemType, StockPeriodRow, InventoryMigrationPlan, InventoryMigrationResult, MigrationCandidate, PageResult, StockCount, StockCountDetail, StockCountStatus, StockLedgerRow,
} from "../../types";
import { fail, notFound } from "../../biz-error";
import { cabinets, powerbanks } from "./device";
import { allMovements } from "./movement";
import { WAREHOUSES, ensurePoolSeed } from "./device-pool";

const counts: (StockCount & { scanned: string[] })[] = [];
let seq = 0;

export const engine = (): InventoryEngine => ({ engine: "SERVICE", configured: true });

export function listStockLedger(q: { warehouseNo?: string; itemType?: string; keyword?: string; page?: number; size?: number } = {})
  : PageResult<StockLedgerRow> {
  const wh = q.warehouseNo ? `WH:${q.warehouseNo}` : null;
  const rows: StockLedgerRow[] = allMovements()
    .filter((m) => (wh ? m.fromRef === wh || m.toRef === wh : m.fromRef?.startsWith("WH:") || m.toRef?.startsWith("WH:")))
    .filter((m) => !q.itemType || m.itemType === q.itemType)
    .filter((m) => !q.keyword || m.itemNo.includes(q.keyword) || (m.sourceRef ?? "").includes(q.keyword))
    .map((m) => {
      const inbound = !!m.toRef?.startsWith("WH:") && (!wh || m.toRef === wh);
      return {
        id: `L${m.id}`, warehouseNo: (inbound ? m.toRef : m.fromRef)?.replace(/^WH:/, "") ?? null, itemType: m.itemType, itemNo: m.itemNo,
        docKind: m.event, docNo: m.sourceRef, qtyDelta: inbound ? 1 : -1, occurredAt: m.occurredAt, operator: m.operatorNo,
      };
    });
  const page = q.page ?? 1;
  const size = q.size ?? 20;
  return { list: rows.slice((page - 1) * size, page * size), total: rows.length };
}

function head(no: string) {
  return counts.find((c) => c.countNo === no) ?? notFound("盘点单", "Stock count", no);
}

function detail(no: string): StockCountDetail {
  const c = head(no);
  const book = cabinets.filter((k) => k.warehouseNo === c.warehouseNo && k.status === "IN_STOCK" && !k.issuedAgentNo).map((k) => k.cabinetNo);
  const counted = c.scanned.filter((x) => book.includes(x)).length;
  const { scanned: _s, ...count } = c;
  return {
    count,
    lines: [{ itemType: "CABINET", bookQty: book.length, countedQty: counted, diffQty: counted - book.length }],
    exceptions: c.scanned.filter((x) => !book.includes(x)).map((serialNo) => ({ serialNo, verdict: "NOT_IN_BOOK" })),
    missingSerials: book.filter((x) => !c.scanned.includes(x)),
  };
}

export function listStockCounts(q: { warehouseNo?: string; status?: string } = {}): StockCount[] {
  return counts.filter((c) => (!q.warehouseNo || c.warehouseNo === q.warehouseNo) && (!q.status || c.status === q.status))
    .map(({ scanned: _s, ...c }) => c);
}

export const getStockCount = (no: string) => detail(no);

export function openStockCount(warehouseNo: string): StockCountDetail {
  if (!WAREHOUSES.some((w) => w.warehouseNo === warehouseNo)) notFound("仓库", "Warehouse", warehouseNo);
  const no = `CNT${String(++seq).padStart(4, "0")}`;
  counts.unshift({ countNo: no, warehouseNo, status: "OPEN", openedAt: new Date().toISOString(), scanned: [] });
  return detail(no);
}

export function scanStockCount(no: string, serialNos: string[]): StockCountDetail {
  const c = head(no);
  if (c.status !== "OPEN") fail("盘点单不在盘点中，不能扫码", "Count is not open");
  const nos = (serialNos ?? []).map((s) => s.trim()).filter(Boolean);
  if (!nos.length) fail("请扫码或输入设备号", "Serial numbers required");
  for (const n of nos) if (!c.scanned.includes(n)) c.scanned.push(n);
  return detail(no);
}

export function moveStockCount(no: string, from: StockCountStatus, to: StockCountStatus): StockCountDetail {
  const c = head(no);
  if (c.status !== from) fail(`盘点单当前是「${c.status}」，不能执行这一步`, "Illegal transition");
  c.status = to;
  return detail(no);
}

export function rejectStockCount(no: string, note: string): StockCountDetail {
  if (!note?.trim()) fail("请填写退回原因", "Note required");
  return moveStockCount(no, "SUBMITTED", "OPEN");
}

export function cancelStockCount(no: string): StockCountDetail {
  const c = head(no);
  if (c.status === "POSTED") fail("已过账的盘点单不能作废", "Posted count cannot be cancelled");
  c.status = "CANCELLED";
  return detail(no);
}

type Cab = (typeof cabinets)[number];
type Pb = (typeof powerbanks)[number];
const STAGE_ORDER: StockStageCode[] = ["INTAKE", "IN_STOCK", "IN_TRANSIT", "DISPATCHED", "ISSUED", "BOUND", "LIVE", "RENTED", "FAULT", "LOST", "RETIRED"];
/** 阶段 → 筛选条件（同后端 CAB_STAGE / PB_STAGE）：总数与下钻共用，口径不分叉。 */
const CAB_STAGE: Partial<Record<StockStageCode, (c: Cab) => boolean>> = {
  INTAKE: (c) => c.status === "IN_STOCK" && !c.warehouseNo && !c.issuedAgentNo && !c.locationNo,
  IN_STOCK: (c) => c.status === "IN_STOCK" && !!c.warehouseNo && !c.issuedAgentNo && !c.locationNo,
  IN_TRANSIT: (c) => c.status === "IN_TRANSIT",
  DISPATCHED: (c) => !!c.issuedAgentNo && !c.locationNo && c.issueClaimedAt === null,
  ISSUED: (c) => !!c.issuedAgentNo && !c.locationNo && c.issueClaimedAt !== null && c.status === "IN_STOCK",
  BOUND: (c) => !!c.locationNo && c.status === "IN_STOCK",
  LIVE: (c) => c.status === "DEPLOYED",
  FAULT: (c) => c.status === "FAULT",
  RETIRED: (c) => c.status === "RETIRED",
};
const PB_STAGE: Partial<Record<StockStageCode, (p: Pb) => boolean>> = {
  IN_STOCK: (p) => p.status === "IN_STOCK" && !!p.warehouseNo,
  LIVE: (p) => p.status === "IN_CABINET",
  RENTED: (p) => p.status === "RENTED",
  FAULT: (p) => p.status === "FAULT",
  LOST: (p) => p.status === "LOST",
  RETIRED: (p) => p.status === "SCRAP" || p.status === "SOLD",
};

/** 统一库存视图（同后端 stages 口径）。 */
export function stages(): StockStage[] {
  ensurePoolSeed();
  const cab = cabinets.filter((c) => !c.archivedAt);
  const pb = powerbanks.filter((p) => !p.archivedAt);
  return STAGE_ORDER.map((stage) => ({
    stage,
    cabinets: CAB_STAGE[stage] ? cab.filter(CAB_STAGE[stage]!).length : 0,
    powerbanks: PB_STAGE[stage] ? pb.filter(PB_STAGE[stage]!).length : 0,
  }));
}

/** 阶段下钻（P2 #6，同后端 stageItems）：某阶段 × 类型的设备清单，最多 2000 台。 */
export function stageItems(stage: string, itemType: IntakeItemType = "CABINET"): StageItems {
  if (!STAGE_ORDER.includes(stage as StockStageCode)) fail("阶段不对", "Invalid stage");
  ensurePoolSeed();
  const st = stage as StockStageCode;
  const items: StageItem[] = itemType === "CABINET"
    ? cabinets.filter((c) => !c.archivedAt && !!CAB_STAGE[st] && CAB_STAGE[st]!(c)).map((c) => ({
      itemType, itemNo: c.cabinetNo, model: c.model ?? null, warehouseNo: c.warehouseNo ?? null,
      agentNo: c.issuedAgentNo ?? c.agentNo ?? null, siteNo: c.siteNo ?? null, locationRef: c.locationNo ?? null, intakeNo: null }))
    : powerbanks.filter((p) => !p.archivedAt && !!PB_STAGE[st] && PB_STAGE[st]!(p)).map((p) => ({
      itemType, itemNo: p.powerbankNo, model: p.vendorCode ?? null, warehouseNo: p.warehouseNo ?? null,
      agentNo: null, siteNo: null, locationRef: p.cabinetNo ?? null, intakeNo: null }));
  return { stage: st, itemType, total: items.length, items: items.slice(0, 2000) };
}

/** 期间报表（同后端本地口径：期末 = 当前在仓 − 期后净进出；期初 = 期末 − 入 + 出）。 */
export function periodSummary(q: { from: string; to: string; warehouseNo?: string }): StockPeriodRow[] {
  ensurePoolSeed();
  const start = q.from;
  const end = new Date(new Date(q.to).getTime() + 86400000).toISOString().slice(0, 10);
  const key = (wh: string, t: string) => `${wh}|${t}`;
  const now = new Map<string, number>();
  for (const c of cabinets) if (c.status === "IN_STOCK" && c.warehouseNo && !c.archivedAt) now.set(key(c.warehouseNo, "CABINET"), (now.get(key(c.warehouseNo, "CABINET")) ?? 0) + 1);
  for (const p of powerbanks) if (p.status === "IN_STOCK" && p.warehouseNo && !p.archivedAt) now.set(key(p.warehouseNo, "POWERBANK"), (now.get(key(p.warehouseNo, "POWERBANK")) ?? 0) + 1);
  const after = new Map<string, number>();
  const ins = new Map<string, Record<string, number>>();
  const outs = new Map<string, Record<string, number>>();
  for (const m of allMovements()) {
    const day = (m.occurredAt ?? "").slice(0, 10);
    if (day < start) continue;
    const inPeriod = day < end;
    const add = (map: Map<string, Record<string, number>>, k: string) => {
      const r = map.get(k) ?? {};
      r[m.event] = (r[m.event] ?? 0) + 1;
      map.set(k, r);
    };
    if (m.toRef?.startsWith("WH:")) {
      const k = key(m.toRef.slice(3), m.itemType);
      if (inPeriod) add(ins, k); else after.set(k, (after.get(k) ?? 0) + 1);
    }
    if (m.fromRef?.startsWith("WH:")) {
      const k = key(m.fromRef.slice(3), m.itemType);
      if (inPeriod) add(outs, k); else after.set(k, (after.get(k) ?? 0) - 1);
    }
  }
  const keys = new Set([...now.keys(), ...ins.keys(), ...outs.keys()]);
  const sum = (r?: Record<string, number>) => Object.values(r ?? {}).reduce((a, b) => a + b, 0);
  return [...keys].sort().flatMap((k) => {
    const [wh, t] = k.split("|");
    if (q.warehouseNo && q.warehouseNo !== wh) return [];
    const closing = (now.get(k) ?? 0) - (after.get(k) ?? 0);
    const inQty = sum(ins.get(k));
    const outQty = sum(outs.get(k));
    return [{ warehouseNo: wh, itemType: t, opening: closing - inQty + outQty, inQty, outQty, closing,
      inByReason: ins.get(k) ?? {}, outByReason: outs.get(k) ?? {} }];
  });
}

// —— 存量迁移（S7）mock：同后端候选口径；迁完的记进 migrated ——
const migrated = new Set<string>();

function candidates(): MigrationCandidate[] {
  ensurePoolSeed();
  const base = { warehouseNo: null, agentNo: null, siteNo: null, claimed: false, reason: null };
  return cabinets.filter((c) => !c.archivedAt && c.status !== "RETIRED").map((c): MigrationCandidate => {
    if (c.status === "IN_TRANSIT") return { ...base, itemType: "CABINET", itemNo: c.cabinetNo, kind: "SKIP", reason: "调拨在途：签收后再迁" };
    if (c.warehouseNo) return { ...base, itemType: "CABINET", itemNo: c.cabinetNo, kind: "IN", warehouseNo: c.warehouseNo };
    if (c.issuedAgentNo) {
      return { ...base, itemType: "CABINET", itemNo: c.cabinetNo, kind: "OUT", agentNo: c.issuedAgentNo, siteNo: c.issuedSiteNo ?? null,
        claimed: c.issueClaimedAt !== null };
    }
    if (c.locationNo) return { ...base, itemType: "CABINET", itemNo: c.cabinetNo, kind: "SKIP", reason: "直营站点的柜：没有商家可出库，暂不迁" };
    return { ...base, itemType: "CABINET", itemNo: c.cabinetNo, kind: "SKIP", reason: "不在仓也未出库给商家（多半在到仓待检）：入库后再迁" };
  });
}

export function migrationPlan(): InventoryMigrationPlan {
  const all = candidates();
  const skippedItems = all.filter((c) => c.kind === "SKIP");
  const pending = all.filter((c) => c.kind !== "SKIP" && !migrated.has(c.itemNo));
  return {
    configured: true, toInbound: pending.filter((c) => c.kind === "IN").length, toIssue: pending.filter((c) => c.kind === "OUT").length,
    skipped: skippedItems.length, done: all.filter((c) => migrated.has(c.itemNo)).length, skippedItems: skippedItems.slice(0, 200),
  };
}

export function runMigration(limit: number): InventoryMigrationResult {
  const pending = candidates().filter((c) => c.kind !== "SKIP" && !migrated.has(c.itemNo));
  const batch = pending.slice(0, limit);
  batch.forEach((c) => migrated.add(c.itemNo));
  return { processed: batch.length, failed: 0, remaining: pending.length - batch.length, failures: [] };
}
