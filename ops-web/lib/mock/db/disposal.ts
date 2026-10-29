// 处置出库（V203）mock：与后端 DisposalServiceImpl 同规则（一次列全不符项，建单即过账）。
import type { Disposal, DisposalDetail, DisposalItem, DisposalKind, DisposalReq, IntakeItemType, PageResult } from "../../types";
import type { PageQ } from "../../api/query";
import { fail, notFound } from "../../biz-error";
import { cabinets, powerbanks } from "./device";
import { WAREHOUSES } from "./device-pool";
import { openIntakeItems } from "./intake";
import { activeSupplierName } from "./inventory-admin";
import { paginate } from "./helpers";
import { recordMovement, ref } from "./movement";

const heads: Disposal[] = [];
const items = new Map<string, DisposalItem[]>();
let seq = 0;

export function listDisposals(q: PageQ & { kind?: DisposalKind; warehouseNo?: string; supplierNo?: string } = {}): PageResult<Disposal> {
  const kw = q.keyword?.trim().toLowerCase();
  return paginate(heads, q.page, q.size, (d) => (!q.kind || d.kind === q.kind) && (!q.warehouseNo || d.warehouseNo === q.warehouseNo)
    && (!q.supplierNo || d.supplierNo === q.supplierNo)
    && (!kw || d.disposalNo.toLowerCase().includes(kw) || d.note.toLowerCase().includes(kw)
      || (items.get(d.disposalNo) ?? []).some((i) => i.itemNo.toLowerCase() === kw)));
}

export function getDisposal(no: string): DisposalDetail {
  const d = heads.find((h) => h.disposalNo === no) ?? notFound("处置单", "Disposal", no);
  return { disposal: { ...d }, items: (items.get(no) ?? []).map((i) => ({ ...i })) };
}

export function createDisposal(x: DisposalReq): DisposalDetail {
  if (x?.kind !== "SCRAP" && x?.kind !== "RETURN_SUPPLIER") fail("请选择处置类型", "Kind required");
  if (!x.note?.trim()) fail("请填写说明", "Note required");
  let supplierName: string | null = null;
  if (x.kind === "SCRAP") {
    if (x.supplierNo) fail("报废不能指定供应商", "Scrap has no supplier");
    if (!x.reasonCode) fail("请选择报废原因", "Reason required");
  } else {
    if (!x.supplierNo) fail("请选择供应商", "Supplier required");
    supplierName = activeSupplierName(x.supplierNo) ?? notFound("供应商", "Supplier", x.supplierNo);
  }
  const wh = WAREHOUSES.find((w) => w.warehouseNo === x.warehouseNo && !w.archivedAt) ?? notFound("仓库", "Warehouse", x.warehouseNo);
  const cabNos = [...new Set((x.cabinetNos ?? []).map((s) => s.trim()).filter(Boolean))];
  const pbNos = [...new Set((x.powerbankNos ?? []).map((s) => s.trim()).filter(Boolean))];
  if (!cabNos.length && !pbNos.length) fail("请至少录入一台设备", "At least one device");
  const bad: string[] = [];
  for (const no of cabNos) {
    const c = cabinets.find((k) => k.cabinetNo === no);
    if (!c) bad.push(`${no}：不存在`);
    else if (c.status !== "IN_STOCK") bad.push(`${no}：不在库（${c.status}）`);
    else if (c.locationNo) bad.push(`${no}：已绑定点位`);
    else if (c.issuedAgentNo) bad.push(`${no}：在商家出库池`);
    else if (c.warehouseNo !== wh.warehouseNo) bad.push(`${no}：不在 ${wh.name}`);
  }
  for (const no of pbNos) {
    const p = powerbanks.find((k) => k.powerbankNo === no);
    if (!p) bad.push(`${no}：不存在`);
    else if (p.status !== "IN_STOCK") bad.push(`${no}：不在库（${p.status}）`);
    else if (p.cabinetNo) bad.push(`${no}：在柜内`);
    else if (p.warehouseNo !== wh.warehouseNo) bad.push(`${no}：不在 ${wh.name}`);
  }
  const all = new Set([...cabNos, ...pbNos]);
  for (const o of openIntakeItems()) if (all.has(o.itemNo)) bad.push(`${o.itemNo}：在未结待检单 ${o.intakeNo} 上，请在待检单里处置`);
  if (bad.length) fail(`以下设备不能办理：${bad.join("；")}`, `Rejected: ${bad.join("; ")}`);

  const no = `DP${String(++seq).padStart(4, "0")}`;
  const why = `${x.kind === "SCRAP" ? "报废" : `退供应商 ${supplierName}`}（处置出库 ${no}）：${x.note.trim()}`;
  const rows: DisposalItem[] = [];
  const out = (itemType: IntakeItemType, itemNo: string) => {
    rows.push({ itemType, itemNo });
    recordMovement({ itemType, itemNo, event: "RETIRE", fromRef: ref.wh(wh.warehouseNo), toRef: "OUT", reason: why, sourceRef: no });
  };
  for (const n of cabNos) { cabinets.find((k) => k.cabinetNo === n)!.status = "RETIRED"; out("CABINET", n); }
  for (const n of pbNos) { powerbanks.find((k) => k.powerbankNo === n)!.status = "SCRAP"; out("POWERBANK", n); }
  heads.unshift({
    disposalNo: no, kind: x.kind, warehouseNo: wh.warehouseNo, warehouseName: wh.name, supplierNo: x.kind === "RETURN_SUPPLIER" ? x.supplierNo! : null,
    supplierName, reasonCode: x.kind === "SCRAP" ? x.reasonCode! : null, note: x.note.trim(), itemCount: rows.length,
    createdBy: "mock", createdAt: new Date().toISOString(),
  });
  items.set(no, rows);
  return getDisposal(no);
}
