import { describe, it, expect } from "vitest";
import * as mt from "./material";

const bal = (wh: string, mat: string) => mt.listMaterialBalances({ warehouseNo: wh, materialNo: mat })[0];

describe("物料（V209 mock）", () => {
  it("期初种子：数量物料与逐件物料都有余额，低于下限标 low", () => {
    expect(bal("WH001", "MT0301").onHand).toBe(300);
    expect(bal("WH001", "MT0101").serialNos).toEqual(["POS-0001", "POS-0002"]);
    expect(bal("WH002", "MT0202").low).toBe(true);
  });

  it("M2 领用不得超过在手，一次列全；领用须有领用人或工单", () => {
    expect(() => mt.createMaterialDoc({ purpose: "REQUISITION", warehouseNo: "WH002", receiverNo: "E1", note: "x",
      lines: [{ materialNo: "MT0202", qty: 7 }, { materialNo: "MT0301", qty: 81 }] })).toThrow(/锁舌.*充电线|在手/);
    expect(() => mt.createMaterialDoc({ purpose: "REQUISITION", warehouseNo: "WH002", note: "x",
      lines: [{ materialNo: "MT0202", qty: 1 }] })).toThrow(/领用人/);
    const d = mt.createMaterialDoc({ purpose: "REQUISITION", warehouseNo: "WH002", woNo: "WO1", note: "维修换锁舌",
      lines: [{ materialNo: "MT0202", qty: 2 }] });
    expect(d.kind).toBe("OUTBOUND");
    expect(bal("WH002", "MT0202").onHand).toBe(4);
  });

  it("逐件物料：调拨移动序列号；入库已在仓的序列号被拒", () => {
    expect(() => mt.createMaterialDoc({ purpose: "TRANSFER", warehouseNo: "WH001", toWarehouseNo: "WH001", note: "x",
      lines: [{ materialNo: "MT0101", qty: 1, serialNos: ["POS-0001"] }] })).toThrow(/相同/);
    mt.createMaterialDoc({ purpose: "TRANSFER", warehouseNo: "WH001", toWarehouseNo: "WH003", note: "调去沙迦",
      lines: [{ materialNo: "MT0101", qty: 1, serialNos: ["POS-0001"] }] });
    expect(bal("WH003", "MT0101").serialNos).toEqual(["POS-0001"]);
    expect(bal("WH001", "MT0101").serialNos).toEqual(["POS-0002"]);
    expect(() => mt.createMaterialDoc({ purpose: "PURCHASE", supplierNo: "SP0002", warehouseNo: "WH001", note: "x",
      lines: [{ materialNo: "MT0101", qty: 1, serialNos: ["POS-0001"] }] })).toThrow(/已在/);
  });

  it("M3 / M4：有流水后跟踪方式锁定、有库存不能归档、内置物料不进单据", () => {
    const m = mt.listMaterials({ keyword: "MT0301" }).list[0];
    expect(() => mt.updateMaterial("MT0301", { ...m, trackingMode: "SERIAL" })).toThrow(/跟踪方式/);
    expect(() => mt.archiveMaterial("MT0301")).toThrow(/还有库存/);
    expect(() => mt.archiveMaterial("MT0001")).toThrow(/内置/);
    expect(() => mt.createMaterialDoc({ purpose: "OPENING", warehouseNo: "WH001", note: "x",
      lines: [{ materialNo: "MT0002", qty: 1, serialNos: ["PB-X"] }] })).toThrow(/设备流程/);
    const n = mt.createMaterial({ name: "测试耗材", category: "CONSUMABLE", trackingMode: "QUANTITY", uom: "个" });
    expect(mt.updateMaterial(n.materialNo, { name: "测试耗材", category: "CONSUMABLE", trackingMode: "SERIAL", uom: "个" }).trackingMode).toBe("SERIAL");
    expect(mt.archiveMaterial(n.materialNo).archivedAt).not.toBeNull();
  });

  it("M5：报废须原因、采购须供应商、单价须币种", () => {
    expect(() => mt.createMaterialDoc({ purpose: "SCRAP", warehouseNo: "WH001", note: "x", lines: [{ materialNo: "MT0301", qty: 1 }] })).toThrow(/原因/);
    expect(() => mt.createMaterialDoc({ purpose: "PURCHASE", warehouseNo: "WH001", note: "x", lines: [{ materialNo: "MT0301", qty: 1 }] })).toThrow(/供应商/);
    expect(() => mt.createMaterialDoc({ purpose: "OPENING", warehouseNo: "WH001", note: "x", lines: [{ materialNo: "MT0301", qty: 1, unitCost: 2 }] })).toThrow(/币种/);
  });
});
