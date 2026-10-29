// 覆盖范围：库存管理 · 物料（V209 起独立模块）——物料档案、物料单据、物料库存。
// 设备（柜 / 宝）的库存动作仍在 device 契约（入库单、出库单、调拨、盘点…），页面已归库存管理。
// 后端未实现：契约先行，口径对齐 ai-shop 库存服务（物料 trackingMode、单据用途、数量行 + 序列号行）。
import type { ArchiveQ } from "../query";
import type {
  PageResult, Material, MaterialReq, MaterialDoc, MaterialDocReq, MaterialDocQ, MaterialBalance, MaterialMinQtyReq,
  MaterialCategory,
} from "../../types";

export interface InventoryApi {
  /** 物料档案：含两条内置物料（机柜、充电宝，只读）。 */
  listMaterials(q?: ArchiveQ & { category?: MaterialCategory }): Promise<PageResult<Material>>;
  createMaterial(x: MaterialReq): Promise<Material>;
  /** 有流水后 trackingMode 不可改（后端拒）。 */
  updateMaterial(materialNo: string, x: MaterialReq): Promise<Material>;
  /** 归档须在所有仓都没有库存；内置物料不可归档。 */
  archiveMaterial(materialNo: string): Promise<Material>;
  unarchiveMaterial(materialNo: string): Promise<Material>;
  /** 物料单据：建单即过账；入库判 device:inventory:inbound，出库 / 调拨判 device:inventory:transfer。 */
  listMaterialDocs(q?: MaterialDocQ): Promise<PageResult<MaterialDoc>>;
  getMaterialDoc(docNo: string): Promise<MaterialDoc>;
  createMaterialDoc(x: MaterialDocReq): Promise<MaterialDoc>;
  /** 物料库存：仓 × 物料（非内置）。 */
  listMaterialBalances(q?: { warehouseNo?: string; materialNo?: string; category?: MaterialCategory; lowOnly?: boolean }): Promise<MaterialBalance[]>;
  /** 物料安全库存：0 = 不预警。写判 device:inventory:warehouse。 */
  saveMaterialMinQty(x: MaterialMinQtyReq): Promise<MaterialBalance>;
}
