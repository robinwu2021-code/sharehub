// 物料（V209）的展示词表：与 lib/types/inventory.ts 的枚举一一对应。
import type { MaterialCategory, MaterialDocKind, MaterialDocPurpose, MaterialScrapReason, MaterialTracking } from "@/lib/types";
import type { StatusMap } from "@/components/ui/status-badge";

export const CATEGORY: StatusMap<MaterialCategory> = {
  DEVICE: { label: "整机设备", tone: "info" }, SPARE_PART: { label: "备件 / 配件", tone: "warning" }, CONSUMABLE: { label: "耗材 / 物料", tone: "muted" },
};
export const TRACKING_LABEL: Record<MaterialTracking, string> = { SERIAL: "逐件（序列号）", QUANTITY: "按数量" };
export const KIND: StatusMap<MaterialDocKind> = {
  INBOUND: { label: "入库", tone: "success" }, OUTBOUND: { label: "出库", tone: "warning" }, TRANSFER: { label: "调拨", tone: "info" },
};
export const PURPOSE_LABEL: Record<MaterialDocPurpose, string> = {
  PURCHASE: "采购入库", OPENING: "期初建账", RETURN: "退回入库", REQUISITION: "领用出库", SCRAP: "报废出库",
  RETURN_SUPPLIER: "退供应商", TRANSFER: "仓间调拨",
};
/** 入库类用途判 device:inventory:inbound，其余判 device:inventory:transfer（与设备单据同口径）。 */
export const INBOUND_PURPOSES: MaterialDocPurpose[] = ["PURCHASE", "OPENING", "RETURN"];
export const OUTBOUND_PURPOSES: MaterialDocPurpose[] = ["REQUISITION", "SCRAP", "RETURN_SUPPLIER", "TRANSFER"];
export const SCRAP_REASON: Record<MaterialScrapReason, string> = { BROKEN: "损坏", OBSOLETE: "淘汰", OTHER: "其他" };
