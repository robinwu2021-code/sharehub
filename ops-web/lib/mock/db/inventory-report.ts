// 库存报表（V206）mock：拼已有的 mock 视图（阶段、期间报表、安全库存）与单据（处置、入库单金额），同后端口径。
import type { InventoryReport, InventoryReportRow } from "../../types";
import { fail } from "../../biz-error";
import { stages, periodSummary } from "./stock";
import { listStockThresholds } from "./inventory-admin";
import { listDisposals } from "./disposal";
import { listIntakes, getIntake } from "./intake";

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

export function inventoryReport(q: { from?: string; to?: string; warehouseNo?: string } = {}): InventoryReport {
  const today = new Date();
  const to = q.to || iso(today);
  const from = q.from || `${to.slice(0, 8)}01`;
  if (from > to) fail("开始日期不能晚于结束日期", "from after to");
  const inRange = (at: string | null | undefined) => !!at && at.slice(0, 10) >= from && at.slice(0, 10) <= to;

  const period = new Map(periodSummary({ from, to, warehouseNo: q.warehouseNo }).map((p) => [`${p.warehouseNo}:${p.itemType}`, p]));
  const rows: InventoryReportRow[] = listStockThresholds(q.warehouseNo).map((t) => {
    const p = period.get(`${t.warehouseNo}:${t.itemType}`);
    return { warehouseNo: t.warehouseNo, warehouseName: t.warehouseName, itemType: t.itemType,
      opening: p?.opening ?? 0, inQty: p?.inQty ?? 0, outQty: p?.outQty ?? 0, closing: p?.closing ?? t.available,
      available: t.available, minQty: t.minQty, low: t.low };
  });

  const disposals: Record<string, number> = {};
  for (const d of listDisposals({ size: 10_000, warehouseNo: q.warehouseNo }).list.filter((x) => inRange(x.createdAt))) {
    const k = d.kind === "SCRAP" ? `SCRAP:${d.reasonCode}` : d.kind;
    disposals[k] = (disposals[k] ?? 0) + d.itemCount;
  }

  const sum = new Map<string, { qty: number; amount: number }>();
  for (const h of listIntakes({ size: 10_000, warehouseNo: q.warehouseNo }).list.filter((x) => x.currency && inRange(x.arrivedAt))) {
    for (const i of getIntake(h.intakeNo).items.filter((x) => x.unitCost != null)) {
      const s = sum.get(h.currency!) ?? { qty: 0, amount: 0 };
      sum.set(h.currency!, { qty: s.qty + 1, amount: s.amount + i.unitCost! });
    }
  }
  const purchases = [...sum.entries()].sort().map(([currency, s]) => ({ currency, ...s }));
  return { from, to, stages: stages(), rows, disposals, purchases };
}
