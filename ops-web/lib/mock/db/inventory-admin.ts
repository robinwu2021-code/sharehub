// 仓库管理与供应商（V201）mock：与后端 WarehouseAdminServiceImpl / SupplierServiceImpl 同规则。
// 归档仓前须：不是默认回仓仓、仓里没有设备、没有未结待检单 —— 一次列全原因。
import type { IntakeItemType, StockThreshold, StockThresholdReq, Supplier, SupplierReq, Warehouse, WarehouseReq, PageResult } from "../../types";
import type { ArchiveQ } from "../../api/query";
import { fail, notFound } from "../../biz-error";
import { cabinets, powerbanks } from "./device";
import { WAREHOUSES, listWarehouses } from "./device-pool";
import { listIntakes } from "./intake";
import { kwHit, nextNo, paginate } from "./helpers";

const now = () => new Date().toISOString();
const name = (x: { name?: string } | undefined) => x?.name?.trim() || fail("请填写名称", "Name required");
const opt = (v: string | null | undefined) => v?.trim() || null;

const whOf = (no: string) => WAREHOUSES.find((w) => w.warehouseNo === no) ?? notFound("仓库", "Warehouse", no);
const view = (no: string) => listWarehouses().find((w) => w.warehouseNo === no)!;

export function createWarehouse(x: WarehouseReq): Warehouse {
  const w: Warehouse = {
    warehouseNo: nextNo("WH", WAREHOUSES, 5, "warehouseNo").replace(/^WH(\d)$/, "WH00$1").replace(/^WH(\d\d)$/, "WH0$1"), name: name(x),
    regionId: opt(x.regionId), address: opt(x.address), isDefault: false, archivedAt: null,
  };
  WAREHOUSES.push(w);
  return view(w.warehouseNo);
}

export function updateWarehouse(no: string, x: WarehouseReq): Warehouse {
  Object.assign(whOf(no), { name: name(x), regionId: opt(x.regionId), address: opt(x.address) });
  return view(no);
}

export function archiveWarehouse(no: string): Warehouse {
  const w = whOf(no);
  if (w.archivedAt) return view(no);
  const why: string[] = [];
  if (view(no).isDefault) why.push("是默认回仓仓");
  const n = cabinets.filter((c) => c.warehouseNo === no).length + powerbanks.filter((p) => p.warehouseNo === no).length;
  if (n) why.push(`仓里还有 ${n} 台设备`);
  const open = listIntakes({ status: "OPEN", warehouseNo: no, size: 1 }).total;
  if (open) why.push(`有 ${open} 张未结待检单`);
  if (why.length) fail(`仓库仍在使用，不能归档：${why.join("；")}`, `Warehouse in use: ${why.join("; ")}`);
  w.archivedAt = now();
  return view(no);
}

export function unarchiveWarehouse(no: string): Warehouse {
  whOf(no).archivedAt = null;
  return view(no);
}

// ————————————————————————————— 供应商 —————————————————————————————

export const suppliers: Supplier[] = [
  { supplierNo: "SP0001", name: "深圳云充科技", contactName: "Li Wei", phone: "+86 755 0000 0001", email: "sales@example.cn", address: "深圳市南山区", note: "机柜主供", archivedAt: null },
  { supplierNo: "SP0002", name: "Gulf Power Devices LLC", contactName: "Omar", phone: "+971 4 000 0002", email: null, address: "Dubai, JAFZA", note: "充电宝", archivedAt: null },
];

const spOf = (no: string) => suppliers.find((s) => s.supplierNo === no) ?? notFound("供应商", "Supplier", no);

export function listSuppliers(q: ArchiveQ = {}): PageResult<Supplier> {
  return paginate(suppliers, q.page, q.size, (s) => (q.showArchived || !s.archivedAt)
    && kwHit(q.keyword, s.supplierNo, s.name, s.contactName, s.phone));
}

const fields = (x: SupplierReq) => ({
  name: name(x), contactName: opt(x.contactName), phone: opt(x.phone), email: opt(x.email),
  address: opt(x.address), note: opt(x.note),
});

export function createSupplier(x: SupplierReq): Supplier {
  const s: Supplier = { supplierNo: nextNo("SP", suppliers, 3, "supplierNo").replace(/^SP(\d)$/, "SP000$1"), ...fields(x), archivedAt: null };
  suppliers.unshift(s);
  return { ...s };
}

export function updateSupplier(no: string, x: SupplierReq): Supplier {
  return { ...Object.assign(spOf(no), fields(x)) };
}

export function archiveSupplier(no: string): Supplier {
  const s = spOf(no);
  s.archivedAt ??= now();
  return { ...s };
}

export function unarchiveSupplier(no: string): Supplier {
  spOf(no).archivedAt = null;
  return { ...spOf(no) };
}

/** 启用中的供应商名称；不存在或已归档返回 null（到货登记校验用）。 */
export function activeSupplierName(no: string): string | null {
  const s = suppliers.find((x) => x.supplierNo === no);
  return s && !s.archivedAt ? s.name : null;
}

// ————————————————————————————— 安全库存（V204） —————————————————————————————

const minQty = new Map<string, number>();
const TYPES: IntakeItemType[] = ["CABINET", "POWERBANK"];

function thresholdRow(w: Warehouse, itemType: IntakeItemType): StockThreshold {
  const available = itemType === "CABINET"
    ? cabinets.filter((c) => c.warehouseNo === w.warehouseNo && c.status === "IN_STOCK" && !c.locationNo && !c.issuedAgentNo && !c.archivedAt).length
    : powerbanks.filter((p) => p.warehouseNo === w.warehouseNo && p.status === "IN_STOCK" && !p.cabinetNo && !p.archivedAt).length;
  const min = minQty.get(`${w.warehouseNo}:${itemType}`) ?? 0;
  return { warehouseNo: w.warehouseNo, warehouseName: w.name, itemType, minQty: min, available, low: min > 0 && available < min };
}

export function listStockThresholds(warehouseNo?: string): StockThreshold[] {
  return WAREHOUSES.filter((w) => !w.archivedAt && (!warehouseNo || w.warehouseNo === warehouseNo))
    .flatMap((w) => TYPES.map((t) => thresholdRow(w, t)));
}

export function saveStockThreshold(x: StockThresholdReq): StockThreshold {
  if (!TYPES.includes(x?.itemType)) fail("设备类型不对", "Invalid item type");
  if (!Number.isInteger(x.minQty) || x.minQty < 0) fail("下限须为非负整数", "Min qty must be a non-negative integer");
  const w = WAREHOUSES.find((k) => k.warehouseNo === x.warehouseNo && !k.archivedAt) ?? notFound("仓库", "Warehouse", x.warehouseNo);
  minQty.set(`${w.warehouseNo}:${x.itemType}`, x.minQty);
  return thresholdRow(w, x.itemType);
}
