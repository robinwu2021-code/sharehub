import { describe, it, expect } from "vitest";
import * as ia from "./inventory-admin";
import * as ik from "./intake";
import { listWarehouses } from "./device-pool";
import { saveSysParam } from "./system";
import { cabinets } from "./device";

describe("仓库管理与供应商（V201 mock）", () => {
  it("新建仓 → 编辑 → 归档后不能收货 → 恢复", () => {
    const w = ia.createWarehouse({ name: "测试仓", address: "A" });
    expect(w.warehouseNo).toMatch(/^WH\d{3}$/);
    expect(ia.updateWarehouse(w.warehouseNo, { name: "改名仓" }).name).toBe("改名仓");
    ia.archiveWarehouse(w.warehouseNo);
    const c = cabinets.find((x) => x.status === "IN_STOCK" && !x.archivedAt)!.cabinetNo;
    expect(() => ik.createIntake({ warehouseNo: w.warehouseNo, cabinetNos: [c] })).toThrow();
    expect(ia.unarchiveWarehouse(w.warehouseNo).archivedAt).toBeNull();
  });

  it("默认回仓仓与有设备的仓不能归档；默认仓随系统参数", () => {
    const def = listWarehouses().find((w) => w.isDefault)!.warehouseNo;
    expect(() => ia.archiveWarehouse(def)).toThrow(/默认回仓/);
    saveSysParam({ paramKey: "inv.return_warehouse", value: "WH003" });
    expect(listWarehouses().find((w) => w.isDefault)?.warehouseNo).toBe("WH003");
    saveSysParam({ paramKey: "inv.return_warehouse", value: def });
  });

  it("供应商：新建 / 归档后不在默认列表、到货登记不能选", () => {
    const s = ia.createSupplier({ name: "测试供应商" });
    expect(ia.listSuppliers({ keyword: "测试供应商" }).total).toBe(1);
    ia.archiveSupplier(s.supplierNo);
    expect(ia.listSuppliers({ keyword: "测试供应商" }).total).toBe(0);
    expect(ia.listSuppliers({ keyword: "测试供应商", showArchived: true }).total).toBe(1);
    expect(() => ik.createIntake({ warehouseNo: "WH002", supplierNo: s.supplierNo, cabinetNos: ["X"] })).toThrow();
    ia.unarchiveSupplier(s.supplierNo);
  });
});

describe("入库单（V202 mock）", () => {
  it("期初建账登记即入库；采购到货带单价合计", () => {
    const free = ["CABRC1", "CABRC2", "CABRC3"];
    for (const no of free) cabinets.push({ ...cabinets[0], cabinetNo: no, sn: no, status: "IN_STOCK", archivedAt: null,
      locationNo: null, issuedAgentNo: null, warehouseNo: null });
    const op = ik.createIntake({ kind: "OPENING", warehouseNo: "WH003", cabinetNos: [free[0]] });
    expect(op.intake.status).toBe("DONE");
    expect(cabinets.find((x) => x.cabinetNo === free[0])!.warehouseNo).toBe("WH003");
    expect(() => ik.createIntake({ warehouseNo: "WH003", unitCosts: { [free[1]]: 10 }, cabinetNos: [free[1]] })).toThrow(/币种/);
    const ar = ik.createIntake({ warehouseNo: "WH003", currency: "aed", unitCosts: { [free[1]]: 10, [free[2]]: 5.5 }, cabinetNos: [free[1], free[2]] });
    expect(ar.intake.totalCost).toBe(15.5);
    expect(ar.intake.currency).toBe("AED");
  });
});
