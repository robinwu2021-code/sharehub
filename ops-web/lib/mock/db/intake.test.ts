import { describe, it, expect } from "vitest";
import * as ik from "./intake";
import { cabinets } from "./device";
import * as da from "./device-asset";
import type { Cabinet } from "../../types";

/** 到仓待检 mock：与后端 IntakeFlowTest 同规则。 */
let n = 0;
const newCabinet = (): string => {
  const no = `CAB-IK${Date.now()}${n++}`;
  cabinets.push({
    cabinetNo: no, sn: no, vendorCode: "cd-tech", model: "X6", locationNo: null, siteNo: null, locationName: null, agentNo: null,
    qcStatus: "PENDING", warehouseNo: null, slotTotal: 8, availableCount: 0, onlineStatus: "OFFLINE", status: "IN_STOCK",
    fwVersion: "1.0.0", lastHeartbeatAt: null, archivedAt: null, issuedAgentNo: null, issuedAt: null,
  } as Cabinet);
  return no;
};
const pass = { powerOn: true, slotsOk: true };
const failQc = { powerOn: false, slotsOk: true, note: "通电失败" };

describe("到仓待检", () => {
  it("★ 登记 → 质检合格 → 入库：所在仓写成待检单的仓，单据完成", () => {
    const c = newCabinet();
    const no = ik.createIntake({ warehouseNo: "WH002", cabinetNos: [c] }).intake.intakeNo;
    const d = ik.inspectIntakeItem(no, "CABINET", c, pass);
    expect(d.items[0]).toMatchObject({ qcResult: "PASSED" });
    expect(d.items[0].qcNo).toBeTruthy();
    const done = ik.inboundIntake(no, [{ itemType: "CABINET", itemNo: c }]);
    expect(done.intake.status).toBe("DONE");
    expect(cabinets.find((x) => x.cabinetNo === c)!.warehouseNo).toBe("WH002");
  });

  it("★ 不合格不能入库、合格不能处置；处置说明必填", () => {
    const bad = newCabinet();
    const good = newCabinet();
    const no = ik.createIntake({ warehouseNo: "WH002", cabinetNos: [bad, good] }).intake.intakeNo;
    ik.inspectIntakeItem(no, "CABINET", bad, failQc);
    ik.inspectIntakeItem(no, "CABINET", good, pass);
    expect(() => ik.inboundIntake(no, [{ itemType: "CABINET", itemNo: bad }])).toThrow();
    expect(() => ik.disposeIntake(no, { items: [{ itemType: "CABINET", itemNo: good }], disposition: "SCRAP", note: "x" })).toThrow();
    expect(() => ik.disposeIntake(no, { items: [{ itemType: "CABINET", itemNo: bad }], disposition: "SCRAP", note: " " })).toThrow();
    const d = ik.disposeIntake(no, { items: [{ itemType: "CABINET", itemNo: bad }], disposition: "RETURN_SUPPLIER", note: "通电失败" });
    expect(d.intake.status).toBe("OPEN");
    expect(cabinets.find((c) => c.cabinetNo === bad)!.status).toBe("RETIRED");
  });

  it("★ 放行凭证：只认本单上新做的通过，用过即废", () => {
    const c = newCabinet();
    const no = ik.createIntake({ warehouseNo: "WH002", cabinetNos: [c] }).intake.intakeNo;
    const old = da.inspectCabinet(c, pass);
    const fresh = ik.inspectIntakeItem(no, "CABINET", c, pass).items[0].qcNo!;
    expect(da.listQcRecords(c).find((r) => r.qcNo === fresh)?.ruleSnapshot).toContain("powerOn");
    expect(da.consumeQc(old.qcNo, c, no)).toBe(false);
    ik.inboundIntake(no, [{ itemType: "CABINET", itemNo: c }]);
    expect(da.listQcRecords(c).find((r) => r.qcNo === fresh)?.consumedBy).toBe(no);
    expect(da.consumeQc(fresh, c, no)).toBe(false);
  });

  it("登记拦截：一台设备不能同时在两张未处理的待检单上；原因一次列全", () => {
    const c = newCabinet();
    ik.createIntake({ warehouseNo: "WH002", cabinetNos: [c] });
    expect(() => ik.createIntake({ warehouseNo: "WH002", cabinetNos: [c, "CAB-NOPE"] })).toThrow(/CAB-NOPE/);
  });

  it("列表计数与筛选", () => {
    const list = ik.listIntakes({ status: "OPEN", page: 1, size: 100 });
    expect(list.list.every((h) => h.status === "OPEN")).toBe(true);
    expect(list.list.some((h) => h.pending > 0)).toBe(true);
  });
});
