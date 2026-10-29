// 库存管理 · 物料（V209）mock：与将来后端同口径（对齐 ai-shop 库存服务），非法动作抛错、不靠界面拦。
//
// 不变式：
//   M1 余额只由单据产生（建单即过账）；单据不改不删，纠错走反向单据
//   M2 数量物料出库不得超过本仓在手；逐件物料出库的序列号须在本仓，入库的序列号不得已在任何仓
//   M3 物料有流水后跟踪方式不可改；在任何仓有库存不可归档
//   M4 内置物料（机柜、充电宝）走设备流程，不进物料单据、不可归档
//   M5 用途与类型绑定：SCRAP 须原因码、RETURN_SUPPLIER / PURCHASE 须供应商、TRANSFER 须调入仓且 ≠ 调出仓
import type {
  Material, MaterialReq, MaterialDoc, MaterialDocReq, MaterialDocQ, MaterialBalance, MaterialMinQtyReq, MaterialDocKind,
  MaterialDocPurpose, MaterialCategory, PageResult,
} from "../../types";
import type { ArchiveQ } from "../../api/query";
import { fail, notFound } from "../../biz-error";
import { WAREHOUSES } from "./device-pool";
import { activeSupplierName } from "./inventory-admin";
import { kwHit, paginate } from "./helpers";

const now = () => new Date().toISOString();

const KIND_OF: Record<MaterialDocPurpose, MaterialDocKind> = {
  PURCHASE: "INBOUND", OPENING: "INBOUND", RETURN: "INBOUND",
  REQUISITION: "OUTBOUND", SCRAP: "OUTBOUND", RETURN_SUPPLIER: "OUTBOUND",
  TRANSFER: "TRANSFER",
};

export const materials: Material[] = [
  { materialNo: "MT0001", name: "共享充电宝机柜", category: "DEVICE", trackingMode: "SERIAL", uom: "台", spec: "按型号见设备台账", vendorCode: null, defaultSupplierNo: null, builtin: true, hasMovements: true, archivedAt: null, createdAt: null },
  { materialNo: "MT0002", name: "充电宝", category: "DEVICE", trackingMode: "SERIAL", uom: "块", spec: null, vendorCode: null, defaultSupplierNo: null, builtin: true, hasMovements: true, archivedAt: null, createdAt: null },
  { materialNo: "MT0101", name: "POS 刷卡终端", category: "DEVICE", trackingMode: "SERIAL", uom: "台", spec: "Sunmi P2", vendorCode: null, defaultSupplierNo: "SP0002", builtin: false, hasMovements: false, archivedAt: null, createdAt: now() },
  { materialNo: "MT0201", name: "机柜主控板", category: "SPARE_PART", trackingMode: "QUANTITY", uom: "块", spec: "S8 主控 v3", vendorCode: null, defaultSupplierNo: "SP0001", builtin: false, hasMovements: false, archivedAt: null, createdAt: now() },
  { materialNo: "MT0202", name: "仓位锁舌模组", category: "SPARE_PART", trackingMode: "QUANTITY", uom: "个", spec: null, vendorCode: null, defaultSupplierNo: "SP0001", builtin: false, hasMovements: false, archivedAt: null, createdAt: now() },
  { materialNo: "MT0301", name: "Type-C 充电线", category: "CONSUMABLE", trackingMode: "QUANTITY", uom: "条", spec: "1 m", vendorCode: null, defaultSupplierNo: null, builtin: false, hasMovements: false, archivedAt: null, createdAt: now() },
  { materialNo: "MT0302", name: "机柜贴纸套装", category: "CONSUMABLE", trackingMode: "QUANTITY", uom: "套", spec: "中英阿三语", vendorCode: null, defaultSupplierNo: null, builtin: false, hasMovements: false, archivedAt: null, createdAt: now() },
];

const docs: MaterialDoc[] = [];
/** 仓号:物料号 → 在手数量。 */
const onHand = new Map<string, number>();
/** 物料号 → 序列号 → 所在仓。 */
const serialAt = new Map<string, Map<string, string>>();
/** 仓号:物料号 → 安全库存下限。 */
const minQty = new Map<string, number>();
let seq = 0;
let seeded = false;

const matOf = (no: string) => materials.find((m) => m.materialNo === no) ?? notFound("物料", "Material", no);
const whOf = (no: string | null | undefined) =>
  WAREHOUSES.find((w) => w.warehouseNo === no && !w.archivedAt) ?? notFound("仓库", "Warehouse", no ?? "");
const key = (wh: string, mat: string) => `${wh}:${mat}`;
const serials = (mat: string) => serialAt.get(mat) ?? serialAt.set(mat, new Map()).get(mat)!;

function ensureSeed() {
  if (seeded) return;
  seeded = true;
  // 期初：主仓的备件与耗材、两台 POS
  createMaterialDoc({ purpose: "OPENING", warehouseNo: "WH001", note: "期初建账", lines: [
    { materialNo: "MT0201", qty: 12 }, { materialNo: "MT0202", qty: 40 }, { materialNo: "MT0301", qty: 300 },
    { materialNo: "MT0302", qty: 25 }, { materialNo: "MT0101", qty: 2, serialNos: ["POS-0001", "POS-0002"] },
  ] });
  createMaterialDoc({ purpose: "OPENING", warehouseNo: "WH002", note: "期初建账", lines: [
    { materialNo: "MT0202", qty: 6 }, { materialNo: "MT0301", qty: 80 },
  ] });
  minQty.set(key("WH002", "MT0202"), 10);
}

// ————————————————————————————— 物料档案 —————————————————————————————

export function listMaterials(q: ArchiveQ & { category?: MaterialCategory } = {}): PageResult<Material> {
  ensureSeed();
  return paginate(materials.map((m) => ({ ...m })), q.page, q.size, (m) => (q.showArchived || !m.archivedAt)
    && (!q.category || m.category === q.category) && kwHit(q.keyword, m.materialNo, m.name, m.spec));
}

function check(x: MaterialReq) {
  if (!x?.name?.trim()) fail("请填写物料名称", "Name required");
  if (!["DEVICE", "SPARE_PART", "CONSUMABLE"].includes(x.category)) fail("请选择物料大类", "Category required");
  if (x.trackingMode !== "SERIAL" && x.trackingMode !== "QUANTITY") fail("请选择跟踪方式", "Tracking mode required");
  if (!x.uom?.trim()) fail("请填写计量单位", "Unit required");
  if (x.defaultSupplierNo && !activeSupplierName(x.defaultSupplierNo)) notFound("供应商", "Supplier", x.defaultSupplierNo);
}

const fields = (x: MaterialReq) => ({
  name: x.name.trim(), category: x.category, trackingMode: x.trackingMode, uom: x.uom.trim(), spec: x.spec?.trim() || null,
  vendorCode: x.vendorCode?.trim() || null, defaultSupplierNo: x.defaultSupplierNo?.trim() || null,
});

export function createMaterial(x: MaterialReq): Material {
  ensureSeed();
  check(x);
  const max = Math.max(0, ...materials.map((m) => Number(m.materialNo.slice(2)) || 0));
  const m: Material = { materialNo: `MT${String(max + 1).padStart(4, "0")}`, ...fields(x), builtin: false, hasMovements: false,
    archivedAt: null, createdAt: now() };
  materials.push(m);
  return { ...m };
}

export function updateMaterial(no: string, x: MaterialReq): Material {
  ensureSeed();
  const m = matOf(no);
  if (m.builtin) fail("内置物料（机柜、充电宝）在设备台账里维护", "Built-in material is read-only");
  check(x);
  if (m.hasMovements && x.trackingMode !== m.trackingMode) fail("物料已有出入库流水，跟踪方式不能再改", "Tracking mode is locked after movements");
  return { ...Object.assign(m, fields(x)) };
}

export function archiveMaterial(no: string): Material {
  ensureSeed();
  const m = matOf(no);
  if (m.builtin) fail("内置物料不能归档", "Built-in material cannot be archived");
  const held = WAREHOUSES.filter((w) => (onHand.get(key(w.warehouseNo, no)) ?? 0) > 0).map((w) => w.name);
  if (held.length) fail(`物料还有库存，不能归档：${held.join("、")}`, `Material still in stock: ${held.join(", ")}`);
  m.archivedAt ??= now();
  return { ...m };
}

export function unarchiveMaterial(no: string): Material {
  ensureSeed();
  matOf(no).archivedAt = null;
  return { ...matOf(no) };
}

// ————————————————————————————— 物料单据 —————————————————————————————

export function listMaterialDocs(q: MaterialDocQ = {}): PageResult<MaterialDoc> {
  ensureSeed();
  return paginate(docs, q.page, q.size, (d) => (!q.kind || d.kind === q.kind) && (!q.purpose || d.purpose === q.purpose)
    && (!q.warehouseNo || d.warehouseNo === q.warehouseNo || d.toWarehouseNo === q.warehouseNo)
    && (!q.materialNo || d.lines.some((l) => l.materialNo === q.materialNo))
    && (!q.keyword || kwHit(q.keyword, d.docNo, d.note, d.woNo, d.receiverNo)
      || d.lines.some((l) => l.serialNos?.some((s) => s.toLowerCase() === q.keyword!.toLowerCase()))));
}

export function getMaterialDoc(no: string): MaterialDoc {
  ensureSeed();
  const d = docs.find((x) => x.docNo === no) ?? notFound("物料单据", "Material document", no);
  return { ...d, lines: d.lines.map((l) => ({ ...l, serialNos: l.serialNos ? [...l.serialNos] : undefined })) };
}

/** 建单即过账：先全部校验（一次列全不符项），再统一记账 —— 不会出现「过了一半」。 */
export function createMaterialDoc(x: MaterialDocReq): MaterialDoc {
  const kind = KIND_OF[x?.purpose];
  if (!kind) fail("请选择单据用途", "Purpose required");
  if (!x.note?.trim()) fail("请填写说明", "Note required");
  if (!x.lines?.length) fail("请至少录入一行物料", "At least one line");
  const wh = whOf(x.warehouseNo);
  const to = kind === "TRANSFER" ? whOf(x.toWarehouseNo) : null;
  if (to && to.warehouseNo === wh.warehouseNo) fail("调入仓不能与调出仓相同", "Source and target warehouse are the same");
  let supplierName: string | null = null;
  if (x.purpose === "PURCHASE" || x.purpose === "RETURN_SUPPLIER") {
    if (!x.supplierNo) fail("请选择供应商", "Supplier required");
    supplierName = activeSupplierName(x.supplierNo) ?? notFound("供应商", "Supplier", x.supplierNo);
  }
  if (x.purpose === "SCRAP" && !x.reasonCode) fail("请选择报废原因", "Reason required");
  if (x.purpose === "REQUISITION" && !x.receiverNo?.trim() && !x.woNo?.trim()) fail("领用须填领用人或关联工单", "Receiver or work order required");
  const costs = x.lines.filter((l) => l.unitCost != null);
  if (costs.some((l) => l.unitCost! < 0)) fail("单价不能为负", "Negative unit cost");
  if (costs.length && !x.currency?.trim()) fail("填了单价请选币种", "Currency required");

  const bad: string[] = [];
  const pending = new Map<string, number>();
  const seen = new Set<string>();
  const lines = x.lines.map((l) => {
    const m = materials.find((k) => k.materialNo === l.materialNo);
    if (!m) { bad.push(`${l.materialNo}：物料不存在`); return null; }
    if (m.builtin) { bad.push(`${m.name}：机柜与充电宝走设备流程（入库单 / 出库池 / 调拨）`); return null; }
    if (m.archivedAt && kind !== "OUTBOUND") { bad.push(`${m.name}：已归档`); return null; }
    let qty = l.qty;
    const sns = [...new Set((l.serialNos ?? []).map((s) => s.trim()).filter(Boolean))];
    if (m.trackingMode === "SERIAL") {
      if (!sns.length) { bad.push(`${m.name}：逐件物料须录入序列号`); return null; }
      qty = sns.length;
      for (const sn of sns) {
        if (seen.has(`${m.materialNo}:${sn}`)) bad.push(`${m.name} ${sn}：同一张单重复`);
        seen.add(`${m.materialNo}:${sn}`);
        const at = serials(m.materialNo).get(sn);
        if (kind === "INBOUND" && at) bad.push(`${m.name} ${sn}：已在 ${whOf(at).name}`);
        if (kind !== "INBOUND" && at !== wh.warehouseNo) bad.push(`${m.name} ${sn}：不在 ${wh.name}`);
      }
    } else {
      if (!Number.isInteger(qty) || qty <= 0) { bad.push(`${m.name}：数量须为正整数`); return null; }
      if (kind !== "INBOUND") {
        const k = key(wh.warehouseNo, m.materialNo);
        const want = (pending.get(k) ?? 0) + qty;
        pending.set(k, want);
        const have = onHand.get(k) ?? 0;
        if (want > have) bad.push(`${m.name}：${wh.name} 在手 ${have} ${m.uom}，不够出 ${want}`);
      }
    }
    return { materialNo: m.materialNo, materialName: m.name, qty, serialNos: m.trackingMode === "SERIAL" ? sns : undefined,
      unitCost: l.unitCost ?? null };
  });
  if (bad.length) fail(`以下物料不能办理：${bad.join("；")}`, `Rejected: ${bad.join("; ")}`);

  for (const l of lines as NonNullable<(typeof lines)[number]>[]) {
    const m = matOf(l.materialNo);
    m.hasMovements = true;
    const from = key(wh.warehouseNo, m.materialNo);
    const sign = kind === "INBOUND" ? 1 : -1;
    onHand.set(from, (onHand.get(from) ?? 0) + sign * l.qty);
    if (to) onHand.set(key(to.warehouseNo, m.materialNo), (onHand.get(key(to.warehouseNo, m.materialNo)) ?? 0) + l.qty);
    for (const sn of l.serialNos ?? []) {
      if (kind === "INBOUND") serials(m.materialNo).set(sn, wh.warehouseNo);
      else if (to) serials(m.materialNo).set(sn, to.warehouseNo);
      else serials(m.materialNo).delete(sn);
    }
  }
  const d: MaterialDoc = {
    docNo: `MD${String(++seq).padStart(4, "0")}`, kind, purpose: x.purpose, warehouseNo: wh.warehouseNo, warehouseName: wh.name,
    toWarehouseNo: to?.warehouseNo ?? null, toWarehouseName: to?.name ?? null, supplierNo: supplierName ? x.supplierNo! : null, supplierName,
    reasonCode: x.purpose === "SCRAP" ? x.reasonCode! : null, receiverNo: x.receiverNo?.trim() || null, woNo: x.woNo?.trim() || null,
    currency: costs.length ? x.currency!.trim().toUpperCase() : null, note: x.note.trim(),
    totalQty: (lines as { qty: number }[]).reduce((a, l) => a + l.qty, 0),
    lines: lines as MaterialDoc["lines"], createdBy: "mock", createdAt: now(),
  };
  docs.unshift(d);
  return getMaterialDoc(d.docNo);
}

// ————————————————————————————— 物料库存 —————————————————————————————

function balanceOf(whNo: string, m: Material): MaterialBalance {
  const w = whOf(whNo);
  const qty = onHand.get(key(whNo, m.materialNo)) ?? 0;
  const min = minQty.get(key(whNo, m.materialNo)) ?? 0;
  return {
    warehouseNo: w.warehouseNo, warehouseName: w.name, materialNo: m.materialNo, materialName: m.name, category: m.category,
    trackingMode: m.trackingMode, uom: m.uom, onHand: qty, minQty: min, low: min > 0 && qty < min,
    serialNos: m.trackingMode === "SERIAL" ? [...serials(m.materialNo)].filter(([, at]) => at === whNo).map(([sn]) => sn).sort() : [],
  };
}

/** 仓 × 物料：有过库存或设过下限的组合才列（全组合会是一张大半为 0 的表）。 */
export function listMaterialBalances(q: { warehouseNo?: string; materialNo?: string; category?: MaterialCategory; lowOnly?: boolean } = {}): MaterialBalance[] {
  ensureSeed();
  const out: MaterialBalance[] = [];
  for (const w of WAREHOUSES.filter((x) => !x.archivedAt && (!q.warehouseNo || x.warehouseNo === q.warehouseNo))) {
    for (const m of materials.filter((x) => !x.builtin && (!q.materialNo || x.materialNo === q.materialNo) && (!q.category || x.category === q.category))) {
      const k = key(w.warehouseNo, m.materialNo);
      if (!onHand.has(k) && !minQty.has(k)) continue;
      const b = balanceOf(w.warehouseNo, m);
      if (!q.lowOnly || b.low) out.push(b);
    }
  }
  return out;
}

export function saveMaterialMinQty(x: MaterialMinQtyReq): MaterialBalance {
  ensureSeed();
  const m = matOf(x?.materialNo);
  if (m.builtin) fail("机柜与充电宝的安全库存在「仓库 › 安全库存」设置", "Use warehouse safety stock for built-in materials");
  if (!Number.isInteger(x.minQty) || x.minQty < 0) fail("下限须为非负整数", "Min qty must be a non-negative integer");
  const w = whOf(x.warehouseNo);
  minQty.set(key(w.warehouseNo, m.materialNo), x.minQty);
  return balanceOf(w.warehouseNo, m);
}
