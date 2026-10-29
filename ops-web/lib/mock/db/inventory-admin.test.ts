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
