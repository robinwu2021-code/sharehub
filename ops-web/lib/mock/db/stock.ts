// 库存流水与盘点（S4）mock：演示按独立进销存引擎（盘点可用）。
// 流水取 mock 履历里进出仓的行（同后端本地引擎口径）；盘点账面 = 该仓在库、未出库的柜机。
import type {
  InventoryEngine, PageResult, StockCount, StockCountDetail, StockCountStatus, StockLedgerRow,
} from "../../types";
import { fail, notFound } from "../../biz-error";
import { cabinets } from "./device";
import { allMovements } from "./movement";
import { WAREHOUSES } from "./device-pool";

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
