// 库存管理 · 物料（V209 起的独立模块；承载方以 ai-shop 库存服务为准，见 docs/technical/独立进销存/）。
// 字段口径对齐 ai-shop 物料：trackingMode = SERIAL（逐件，按序列号）/ QUANTITY（按数量），有流水后不可改。
// 后端 V210（InventoryController）；物料单据与物料库存的账在 ai-shop 库存服务，只在独立进销存引擎下可用。

/** 物料大类：整机设备 / 备件与配件 / 耗材与物料。 */
export type MaterialCategory = "DEVICE" | "SPARE_PART" | "CONSUMABLE";
/** 跟踪方式：逐件（序列号）/ 按数量。 */
export type MaterialTracking = "SERIAL" | "QUANTITY";

/**
 * 物料档案（GET /api/ops/inventory/materials）。
 * builtin = 机柜、充电宝两条内置物料：它们的账走设备流程（入库单、出库单、调拨），不能在物料单据里出入，也不能归档。
 */
export interface Material {
  materialNo: string;
  name: string;
  category: MaterialCategory;
  trackingMode: MaterialTracking;
  /** 计量单位（个、条、箱…）。 */
  uom: string;
  spec: string | null;
  /** 对应设备厂商（厂商接入），可空。 */
  vendorCode: string | null;
  /** 默认供应商（采购入库预填）。 */
  defaultSupplierNo: string | null;
  builtin: boolean;
  /** 已有出入库流水：跟踪方式此后不可改。 */
  hasMovements: boolean;
  archivedAt: string | null;
  createdAt: string | null;
}
export interface MaterialReq {
  name: string;
  category: MaterialCategory;
  trackingMode: MaterialTracking;
  uom: string;
  spec?: string | null;
  vendorCode?: string | null;
  defaultSupplierNo?: string | null;
}

/** 物料单据大类：入库 / 出库 / 调拨（调拨 = 一出一入，同一张单）。 */
export type MaterialDocKind = "INBOUND" | "OUTBOUND" | "TRANSFER";
/**
 * 用途（同 ai-shop 单据用途）：
 * 入库 PURCHASE 采购 / OPENING 期初 / RETURN 退回入库；
 * 出库 REQUISITION 领用（维修、安装用料）/ SCRAP 报废 / RETURN_SUPPLIER 退供应商；
 * 调拨 TRANSFER。
 */
export type MaterialDocPurpose = "PURCHASE" | "OPENING" | "RETURN" | "REQUISITION" | "SCRAP" | "RETURN_SUPPLIER" | "TRANSFER";
/** 报废原因（SCRAP 必填）：同处置出库。 */
export type MaterialScrapReason = "BROKEN" | "OBSOLETE" | "OTHER";

export interface MaterialDocLine {
  materialNo: string;
  materialName?: string;
  /** 数量物料填数量；逐件物料 = serialNos.length。 */
  qty: number;
  serialNos?: string[];
  unitCost?: number | null;
}
/** 物料单据（建单即过账，同处置出库口径；纠错走反向单据，不改历史）。 */
export interface MaterialDoc {
  docNo: string;
  kind: MaterialDocKind;
  purpose: MaterialDocPurpose;
  warehouseNo: string;
  warehouseName: string | null;
  /** 调拨的调入仓。 */
  toWarehouseNo: string | null;
  toWarehouseName: string | null;
  supplierNo: string | null;
  supplierName: string | null;
  reasonCode: MaterialScrapReason | null;
  /** 领用：领用人工号 / 关联工单号。 */
  receiverNo: string | null;
  woNo: string | null;
  currency: string | null;
  note: string;
  totalQty: number;
  lines: MaterialDocLine[];
  createdBy: string | null;
  createdAt: string | null;
}
export interface MaterialDocReq {
  purpose: MaterialDocPurpose;
  warehouseNo: string;
  toWarehouseNo?: string | null;
  supplierNo?: string | null;
  reasonCode?: MaterialScrapReason | null;
  receiverNo?: string | null;
  woNo?: string | null;
  currency?: string | null;
  note: string;
  lines: MaterialDocLine[];
}
export interface MaterialDocQ {
  page?: number;
  size?: number;
  keyword?: string;
  kind?: MaterialDocKind;
  purpose?: MaterialDocPurpose;
  warehouseNo?: string;
  materialNo?: string;
}

/** 物料库存（GET /api/ops/inventory/material-balances）：仓 × 物料，只列非内置物料。 */
export interface MaterialBalance {
  warehouseNo: string;
  warehouseName: string;
  materialNo: string;
  materialName: string;
  category: MaterialCategory;
  trackingMode: MaterialTracking;
  uom: string;
  onHand: number;
  /** 安全库存下限，0 = 不预警。 */
  minQty: number;
  low: boolean;
  /** 逐件物料在本仓的序列号。 */
  serialNos: string[];
}
export interface MaterialMinQtyReq {
  warehouseNo: string;
  materialNo: string;
  minQty: number;
}
