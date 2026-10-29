// 覆盖范围：库存管理 · 物料（V209）。端点前缀沿用 /api/ops/inventory。
//
// ⚠️ 后端缺口（2026-10-29 契约先行，用户确认「先上契约，显式登记缺口」）：以下十个端点后端都还没有。
//    漂移棘轮（lib/drift.test.ts）的「契约缺口」上限因此显式调到 10；菜单三项未置 ready，真实后端模式下灰显。
//    后端实现时以 ai-shop 库存服务为账本（物料 → items/by-ref、单据 → inbounds / outbounds、余额 → balances），
//    本服务只做宿主转发与主数据（docs/technical/设计规范-库存管理菜单.md §四）。
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
