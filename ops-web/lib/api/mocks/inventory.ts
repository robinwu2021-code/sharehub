// 覆盖范围：库存管理 · 物料（V209）。规则在 mock/db/material.ts（与将来后端同口径，非法动作抛错）。
import type { InventoryApi } from "../contracts/inventory";
import * as mt from "../../mock/db/material";
import { wait } from "./_wait";

export const inventoryMock: InventoryApi = {
  listMaterials: async (q) => wait(mt.listMaterials(q)),
  createMaterial: async (x) => wait(mt.createMaterial(x), 350),
  updateMaterial: async (no, x) => wait(mt.updateMaterial(no, x), 350),
  archiveMaterial: async (no) => wait(mt.archiveMaterial(no), 350),
  unarchiveMaterial: async (no) => wait(mt.unarchiveMaterial(no), 350),
  listMaterialDocs: async (q) => wait(mt.listMaterialDocs(q)),
  getMaterialDoc: async (no) => wait(mt.getMaterialDoc(no)),
  createMaterialDoc: async (x) => wait(mt.createMaterialDoc(x), 400),
  listMaterialBalances: async (q) => wait(mt.listMaterialBalances(q)),
  saveMaterialMinQty: async (x) => wait(mt.saveMaterialMinQty(x), 300),
};
