// 到仓待检（V192）mock：与后端 IntakeServiceImpl 同规则（登记拦截一次列全、只有合格能入库、只有不合格能处置）。
import type {
  Cabinet, Intake, IntakeDetail, IntakeItem, IntakeCreateReq, IntakeItemRef, IntakeDisposeReq, IntakeItemType,
  IntakeKind, IntakeStatus, PageResult, QcReq,
} from "../../types";
import { fail, notFound } from "../../biz-error";
import { cabinets, powerbanks } from "./device";
import { consumeQc, inspectCabinet, inspectPowerbank } from "./device-asset";
import { WAREHOUSES } from "./device-pool";
import { activeSupplierName } from "./inventory-admin";
import { sysParams } from "./system";
import { paginate } from "./helpers";
import { recordMovement, ref } from "./movement";

interface Head extends Omit<Intake, "total" | "pending" | "passed" | "failed" | "inbound" | "disposed" | "totalCost"> {}

const heads: Head[] = [];
const items = new Map<string, IntakeItem[]>();
let seq = 0;
let seeded = false;
const now = () => new Date().toISOString();

/** 演示：三台新到货的柜一张到货单；一台撤机回仓的柜一张回仓单（后端由回仓调拨签收生成）。 */
function ensureSeed() {
  if (seeded) return;
  seeded = true;
  const demo = ["CAB1200", "CAB1201", "CAB1202", "CAB1203"];
  for (const no of demo) {
    if (!cabinets.some((c) => c.cabinetNo === no)) {
      cabinets.push({
        cabinetNo: no, sn: `SN-${no}`, vendorCode: "cd-tech", model: "X6", locationNo: null, siteNo: null, locationName: null,
        agentNo: null, qcStatus: "PENDING", warehouseNo: null, slotTotal: 8, availableCount: 0, onlineStatus: "OFFLINE",
        status: "IN_STOCK", fwVersion: "1.0.0", lastHeartbeatAt: null, archivedAt: null, issuedAgentNo: null, issuedAt: null,
      } as Cabinet);
    }
  }
  insert({ warehouseNo: "WH001", sourceRef: "DN-2026-1029", cabinetNos: demo.slice(0, 3) });
  insert({ warehouseNo: "WH001", sourceRef: "WO20261028001", note: "回仓调拨 TR20261028001", cabinetNos: ["CAB1203"] }, "RETURN");
}

function counts(no: string) {
  const rows = items.get(no) ?? [];
  const c = { total: rows.length, pending: 0, passed: 0, failed: 0, inbound: 0, disposed: 0 };
  for (const r of rows) {
    if (r.disposition === "INBOUND") c.inbound++;
    else if (r.disposition !== "PENDING") c.disposed++;
    else if (r.qcResult === "PASSED") c.passed++;
    else if (r.qcResult === "FAILED") c.failed++;
    else c.pending++;
  }
  return c;
}

const vo = (h: Head): Intake => {
  const costs = (items.get(h.intakeNo) ?? []).map((i) => i.unitCost).filter((v): v is number => v != null);
  return { ...h, ...counts(h.intakeNo), totalCost: costs.length ? costs.reduce((a, b) => a + b, 0) : null };
};
const head = (no: string) => heads.find((h) => h.intakeNo === no) ?? notFound("待检单", "Intake", no);
const detail = (no: string): IntakeDetail => ({ intake: vo(head(no)), items: (items.get(no) ?? []).map((i) => ({ ...i })) });

function openHead(no: string) {
  const h = head(no);
  if (h.status !== "OPEN") fail(`待检单 ${no} 已完成，不能再操作`, `Intake ${no} is completed`);
  return h;
}

function itemOf(no: string, type: IntakeItemType, itemNo: string) {
  return (items.get(no) ?? []).find((i) => i.itemType === type && i.itemNo === itemNo) ?? notFound("设备", "Device", itemNo);
}

function closeIfDone(h: Head) {
  if ((items.get(h.intakeNo) ?? []).every((i) => i.disposition !== "PENDING")) h.status = "DONE";
}

function insert(x: IntakeCreateReq, kind: IntakeKind = "ARRIVAL"): IntakeDetail {
  const wh = WAREHOUSES.find((w) => w.warehouseNo === x.warehouseNo && !w.archivedAt) ?? notFound("仓库", "Warehouse", x.warehouseNo);
  const supplierNo = x.supplierNo?.trim() || null;
  const supplierName = supplierNo ? activeSupplierName(supplierNo) ?? notFound("供应商", "Supplier", supplierNo) : null;
  const no = `IK${String(++seq).padStart(4, "0")}`;
  heads.unshift({
    intakeNo: no, kind, warehouseNo: wh.warehouseNo, warehouseName: wh.name, sourceRef: x.sourceRef?.trim() || null, supplierNo, supplierName,
    currency: x.currency?.trim().toUpperCase() || null,
    arrivedAt: x.arrivedAt || now(), status: "OPEN", note: x.note?.trim() || null, createdBy: "mock", createdAt: now(),
  });
  const mk = (itemType: IntakeItemType, itemNo: string): IntakeItem => {
    const c = itemType === "CABINET" ? cabinets.find((k) => k.cabinetNo === itemNo) : undefined;
    const p = itemType === "POWERBANK" ? powerbanks.find((k) => k.powerbankNo === itemNo) : undefined;
    return {
      itemType, itemNo, unitCost: x.unitCosts?.[itemNo] ?? null, sn: c?.sn ?? p?.sn ?? null, model: c?.model ?? null, qcNo: null, qcResult: "PENDING",
      disposition: "PENDING", dispositionNote: null, handledBy: null, handledAt: null,
    };
  };
  items.set(no, [...(x.cabinetNos ?? []).map((n) => mk("CABINET", n)), ...(x.powerbankNos ?? []).map((n) => mk("POWERBANK", n))]);
  return detail(no);
}

export function listIntakes(q: { page?: number; size?: number; keyword?: string; status?: IntakeStatus; warehouseNo?: string } = {}): PageResult<Intake> {
  ensureSeed();
  const kw = q.keyword?.trim().toLowerCase();
  return paginate(heads.map(vo), q.page, q.size, (h) =>
    (!q.status || h.status === q.status) && (!q.warehouseNo || h.warehouseNo === q.warehouseNo)
    && (!kw || h.intakeNo.toLowerCase().includes(kw) || (h.sourceRef ?? "").toLowerCase().includes(kw)));
}

/** 未结待检单上的设备号（处置出库据此拦截：在待检单上的要在待检单里处置）。 */
export function openIntakeItems(): { itemNo: string; intakeNo: string }[] {
  ensureSeed();
  return [...items.entries()].flatMap(([intakeNo, xs]) => xs.filter((i) => i.disposition === "PENDING").map((i) => ({ itemNo: i.itemNo, intakeNo })));
}

export function getIntake(no: string): IntakeDetail {
  ensureSeed();
  return detail(no);
}

export function createIntake(x: IntakeCreateReq): IntakeDetail {
  ensureSeed();
  if (!x?.warehouseNo) fail("请选择仓库", "Warehouse required");
  const kind = x.kind ?? "ARRIVAL";
  if (kind !== "ARRIVAL" && kind !== "OPENING") fail("入库类型不对", "Invalid kind");
  if (kind === "OPENING" && !x.openingBasis?.trim()) fail("期初建账请填期初依据", "Opening basis required");
  const costs = Object.values(x.unitCosts ?? {});
  if (costs.some((v) => v < 0)) fail("单价不能为负", "Negative unit cost");
  if (costs.length && !x.currency?.trim()) fail("填了单价请选币种", "Currency required");
  const cabNos = [...new Set((x.cabinetNos ?? []).map((s) => s.trim()).filter(Boolean))];
  const pbNos = [...new Set((x.powerbankNos ?? []).map((s) => s.trim()).filter(Boolean))];
  if (!cabNos.length && !pbNos.length) fail("请至少录入一台设备", "At least one device");
  const bad: string[] = [];
  const openNos = new Set([...items.values()].flat().filter((i) => i.disposition === "PENDING").map((i) => `${i.itemType}:${i.itemNo}`));
  for (const no of cabNos) {
    const c = cabinets.find((k) => k.cabinetNo === no);
    if (!c) bad.push(`${no}：不存在`);
    else if (c.archivedAt) bad.push(`${no}：已归档`);
    else if (c.status !== "IN_STOCK") bad.push(`${no}：不在库（${c.status}）`);
    else if (c.locationNo) bad.push(`${no}：已绑定点位`);
    else if (c.issuedAgentNo) bad.push(`${no}：已出库给商家`);
    else if (openNos.has(`CABINET:${no}`)) bad.push(`${no}：已在未处理的待检单上`);
  }
  for (const no of pbNos) {
    const p = powerbanks.find((k) => k.powerbankNo === no);
    if (!p) bad.push(`${no}：不存在`);
    else if (p.archivedAt) bad.push(`${no}：已归档`);
    else if (p.status !== "IN_STOCK") bad.push(`${no}：不在库（${p.status}）`);
    else if (p.cabinetNo) bad.push(`${no}：在柜内`);
    else if (openNos.has(`POWERBANK:${no}`)) bad.push(`${no}：已在未处理的待检单上`);
  }
  if (bad.length) fail(`以下设备不能办理：${bad.join("；")}`, `Rejected: ${bad.join("; ")}`);
  const d = insert({ ...x, cabinetNos: cabNos, powerbankNos: pbNos }, kind);
  // 期初建账：登记即入库（OPEN-）；充电宝免检（inv.qc.powerbank_policy = SKIP）：采购到货的宝登记即入库（NOQC-），柜照常质检
  const skipPb = kind !== "OPENING" && sysParams.find((p) => p.paramKey === "inv.qc.powerbank_policy")?.value === "SKIP";
  if (kind !== "OPENING" && !(skipPb && pbNos.length)) return d;
  const h = head(d.intake.intakeNo);
  for (const i of items.get(h.intakeNo) ?? []) {
    if (kind !== "OPENING" && i.itemType !== "POWERBANK") continue;
    if (i.itemType === "CABINET") cabinets.find((k) => k.cabinetNo === i.itemNo)!.warehouseNo = h.warehouseNo;
    else powerbanks.find((k) => k.powerbankNo === i.itemNo)!.warehouseNo = h.warehouseNo;
    recordMovement({ itemType: i.itemType, itemNo: i.itemNo, event: "INBOUND", toRef: ref.wh(h.warehouseNo),
      reason: kind === "OPENING" ? `期初建账 OPEN-${i.itemNo}` : `充电宝免检 NOQC-${i.itemNo}`, sourceRef: h.intakeNo });
    Object.assign(i, { disposition: "INBOUND", handledBy: "mock", handledAt: now() });
  }
  closeIfDone(h);
  return detail(h.intakeNo);
}

export function inspectIntakeItem(no: string, type: IntakeItemType, itemNo: string, req: QcReq): IntakeDetail {
  openHead(no);
  const it = itemOf(no, type, itemNo);
  if (it.disposition !== "PENDING") fail(`设备 ${itemNo} 已入库或已处置`, "Already handled");
  const rec = type === "CABINET" ? inspectCabinet(itemNo, req, no) : inspectPowerbank(itemNo, req, no);
  Object.assign(it, { qcNo: rec.qcNo, qcResult: rec.result });
  return detail(no);
}

export function inboundIntake(no: string, refs: IntakeItemRef[]): IntakeDetail {
  const h = openHead(no);
  if (!refs?.length) fail("请选择设备", "Select devices");
  const picked = refs.map((r) => itemOf(no, r.itemType, r.itemNo));
  const bad = picked.filter((i) => i.disposition !== "PENDING" || i.qcResult !== "PASSED")
    .map((i) => `${i.itemNo}：${i.disposition === "PENDING" ? "质检未通过或未质检" : "已处理"}`);
  if (bad.length) fail(`以下设备不能办理：${bad.join("；")}`, `Rejected: ${bad.join("; ")}`);
  for (const i of picked) {
    if (!consumeQc(i.qcNo, i.itemNo, no)) fail(`设备 ${i.itemNo} 的质检凭证不可用：须在本单上重新质检通过，且每份凭证只能入库一次`, "QC voucher not usable");
    if (i.itemType === "CABINET") {
      const c = cabinets.find((k) => k.cabinetNo === i.itemNo)!;
      if (c.status !== "IN_STOCK" || c.locationNo || c.issuedAgentNo) fail("设备状态已变化，请刷新", "State changed");
      c.warehouseNo = h.warehouseNo;
    } else {
      const p = powerbanks.find((k) => k.powerbankNo === i.itemNo)!;
      if (p.status !== "IN_STOCK" || p.cabinetNo) fail("设备状态已变化，请刷新", "State changed");
      p.warehouseNo = h.warehouseNo;
    }
    recordMovement({ itemType: i.itemType, itemNo: i.itemNo, event: "INBOUND", toRef: ref.wh(h.warehouseNo),
      reason: `质检凭证 ${i.qcNo}`, sourceRef: no });
    Object.assign(i, { disposition: "INBOUND", handledBy: "mock", handledAt: now() });
  }
  closeIfDone(h);
  return detail(no);
}

export function disposeIntake(no: string, x: IntakeDisposeReq): IntakeDetail {
  const h = openHead(no);
  if (x?.disposition !== "RETURN_SUPPLIER" && x?.disposition !== "SCRAP") fail("请选择处置方式", "Disposition required");
  if (!x.note?.trim()) fail("请填写说明", "Note required");
  if (!x.items?.length) fail("请选择设备", "Select devices");
  const picked = x.items.map((r) => itemOf(no, r.itemType, r.itemNo));
  const bad = picked.filter((i) => i.disposition !== "PENDING" || i.qcResult !== "FAILED")
    .map((i) => `${i.itemNo}：${i.disposition === "PENDING" ? "质检未判不合格" : "已处理"}`);
  if (bad.length) fail(`以下设备不能办理：${bad.join("；")}`, `Rejected: ${bad.join("; ")}`);
  for (const i of picked) {
    // 退供应商与报废都是离开库存：设备落到终态（同后端）
    if (i.itemType === "CABINET") {
      const c = cabinets.find((k) => k.cabinetNo === i.itemNo);
      if (c) c.status = "RETIRED";
    } else {
      const p = powerbanks.find((k) => k.powerbankNo === i.itemNo);
      if (p) p.status = "SCRAP";
    }
    recordMovement({ itemType: i.itemType, itemNo: i.itemNo, event: "RETIRE", toRef: "OUT", reason: x.note, sourceRef: no });
    Object.assign(i, { disposition: x.disposition, dispositionNote: x.note.trim(), handledBy: "mock", handledAt: now() });
  }
  closeIfDone(h);
  return detail(no);
}
