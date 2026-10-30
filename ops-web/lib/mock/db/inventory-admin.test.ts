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
    const op = ik.createIntake({ kind: "OPENING", warehouseNo: "WH003", cabinetNos: [free[0]], openingBasis: "期初盘点表" });
    expect(op.intake.status).toBe("DONE");
    expect(cabinets.find((x) => x.cabinetNo === free[0])!.warehouseNo).toBe("WH003");
    expect(() => ik.createIntake({ warehouseNo: "WH003", unitCosts: { [free[1]]: 10 }, cabinetNos: [free[1]] })).toThrow(/币种/);
    const ar = ik.createIntake({ warehouseNo: "WH003", currency: "aed", unitCosts: { [free[1]]: 10, [free[2]]: 5.5 }, cabinetNos: [free[1], free[2]] });
    expect(ar.intake.totalCost).toBe(15.5);
    expect(ar.intake.currency).toBe("AED");
  });
});

describe("处置出库（V203 mock）", () => {
  it("报废须原因；退供应商须供应商；不在该仓的一次列出；过账后落终态", async () => {
    const dsp = await import("./disposal");
    for (const no of ["CABDP1", "CABDP2"]) cabinets.push({ ...cabinets[0], cabinetNo: no, sn: no, status: "IN_STOCK", archivedAt: null,
      locationNo: null, issuedAgentNo: null, warehouseNo: no === "CABDP1" ? "WH002" : "WH003" });
    expect(() => dsp.createDisposal({ kind: "SCRAP", warehouseNo: "WH002", note: "x", cabinetNos: ["CABDP1"] })).toThrow(/原因/);
    expect(() => dsp.createDisposal({ kind: "RETURN_SUPPLIER", warehouseNo: "WH002", note: "x", cabinetNos: ["CABDP1"] })).toThrow(/供应商/);
    expect(() => dsp.createDisposal({ kind: "SCRAP", reasonCode: "BROKEN", warehouseNo: "WH002", note: "x", cabinetNos: ["CABDP1", "CABDP2"] })).toThrow(/CABDP2/);
    const d = dsp.createDisposal({ kind: "RETURN_SUPPLIER", supplierNo: "SP0001", warehouseNo: "WH002", note: "批次不良", cabinetNos: ["CABDP1"] });
    expect(d.disposal.supplierName).toBe("深圳云充科技");
    expect(cabinets.find((c) => c.cabinetNo === "CABDP1")!.status).toBe("RETIRED");
    expect(dsp.listDisposals({ keyword: "CABDP1" }).total).toBe(1);
  });
});

describe("安全库存（V204 mock）", () => {
  it("每仓柜 / 宝各一行；设下限后可用量不足即 low；负数被拒", () => {
    const w = ia.createWarehouse({ name: "安全库存仓" });
    expect(ia.listStockThresholds(w.warehouseNo)).toHaveLength(2);
    expect(() => ia.saveStockThreshold({ warehouseNo: w.warehouseNo, itemType: "CABINET", minQty: -1 })).toThrow();
    expect(ia.saveStockThreshold({ warehouseNo: w.warehouseNo, itemType: "CABINET", minQty: 2 }).low).toBe(true);
    cabinets.push({ ...cabinets[0], cabinetNo: "CABSS1", sn: "CABSS1", status: "IN_STOCK", archivedAt: null, locationNo: null, issuedAgentNo: null, warehouseNo: w.warehouseNo });
    cabinets.push({ ...cabinets[0], cabinetNo: "CABSS2", sn: "CABSS2", status: "IN_STOCK", archivedAt: null, locationNo: null, issuedAgentNo: null, warehouseNo: w.warehouseNo });
    expect(ia.listStockThresholds(w.warehouseNo).find((t) => t.itemType === "CABINET")!.low).toBe(false);
  });
});

describe("发货物流（V205 mock）", () => {
  it("填了才记；按单据、按设备号可查；超长拒", async () => {
    const sh = await import("./shipment");
    expect(sh.recordShipment("TRANSFER", "TR-X", ["A1"], {})).toBeNull();
    sh.recordShipment("TRANSFER", "TR-X", ["A1", "A2"], { carrier: "Aramex", trackingNo: "AX1" });
    expect(sh.listShipments({ docType: "TRANSFER", docRef: "TR-X" })).toHaveLength(1);
    expect(sh.listShipments({ itemNo: "A2" })[0].trackingNo).toBe("AX1");
    expect(() => sh.recordShipment("ISSUE", "AG1", ["A3"], { trackingNo: "X".repeat(65) })).toThrow();
    expect(() => sh.listShipments({})).toThrow();
  });
});

describe("阶段下钻（P2 #6 mock）", () => {
  it("每个阶段 × 类型：清单台数 = 总览台数", async () => {
    const st = await import("./stock");
    for (const s of st.stages()) {
      expect(st.stageItems(s.stage, "CABINET").total).toBe(s.cabinets);
      expect(st.stageItems(s.stage, "POWERBANK").total).toBe(s.powerbanks);
    }
    expect(() => st.stageItems("NOPE")).toThrow();
  });
});

describe("库存设置与供应商绩效（V207 mock）", () => {
  it("宝免检：采购到货的宝登记即入库、柜仍待检；绩效按到货计", async () => {
    const { sysParams } = await import("./system");
    const { powerbanks } = await import("./device");
    const p = sysParams.find((x) => x.paramKey === "inv.qc.powerbank_policy")!;
    p.value = "SKIP";
    cabinets.push({ ...cabinets[0], cabinetNo: "CABQP1", sn: "CABQP1", status: "IN_STOCK", archivedAt: null, locationNo: null, issuedAgentNo: null, warehouseNo: null });
    powerbanks.push({ ...powerbanks[0], powerbankNo: "PBQP1", sn: "PBQP1", status: "IN_STOCK", archivedAt: null, cabinetNo: null, warehouseNo: null });
    const s = ia.createSupplier({ name: "绩效供应商" });
    const d = ik.createIntake({ warehouseNo: "WH002", supplierNo: s.supplierNo, cabinetNos: ["CABQP1"], powerbankNos: ["PBQP1"] });
    p.value = "FULL";
    expect(d.intake.inbound).toBe(1);
    expect(d.intake.pending).toBe(1);
    expect(powerbanks.find((x) => x.powerbankNo === "PBQP1")!.warehouseNo).toBe("WH002");
    const st = ia.supplierStats().find((x) => x.supplierNo === s.supplierNo)!;
    expect(st.received).toBe(2);
    expect(st.passRate).toBe(1);
  });
});
