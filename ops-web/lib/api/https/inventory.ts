// 覆盖范围：库存管理 · 物料（V209）。端点前缀沿用 /api/ops/inventory。
//
// 后端 V210：InventoryController（物料档案为宿主主数据；物料单据与物料库存的账在 ai-shop 库存服务，
// 只在独立进销存引擎下可用，LOCAL 下返回 409 error.inventory.material_service_only）。
import type { InventoryApi } from "../contracts/inventory";
import { client } from "../http-client";

export const inventoryHttp: InventoryApi = {
  listMaterials: (q) => client.get("/api/ops/inventory/materials", q),
  createMaterial: (x) => client.post("/api/ops/inventory/materials", x),
  updateMaterial: (no, x) => client.post(`/api/ops/inventory/materials/${no}`, x),
  archiveMaterial: (no) => client.post(`/api/ops/inventory/materials/${no}/archive`, {}),
  unarchiveMaterial: (no) => client.post(`/api/ops/inventory/materials/${no}/unarchive`, {}),
  listMaterialDocs: (q) => client.get("/api/ops/inventory/material-docs", q),
  getMaterialDoc: (no) => client.get(`/api/ops/inventory/material-docs/${no}`),
  createMaterialDoc: (x) => client.post("/api/ops/inventory/material-docs", x),
  listMaterialBalances: (q) => client.get("/api/ops/inventory/material-balances", q ?? {}),
  saveMaterialMinQty: (x) => client.post("/api/ops/inventory/material-thresholds", x),
};
