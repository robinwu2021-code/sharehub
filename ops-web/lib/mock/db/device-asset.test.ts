import { describe, it, expect } from "vitest";
import * as da from "./device-asset";
import { cabinets, powerbanks, inventoryTransfers, saveInventoryTransfer, getInventoryTransfer } from "./device";
import { POWERBANK_TRANSITIONS } from "../../types";
import type { Cabinet, Powerbank, PowerbankStatus } from "../../types";

/**
 * 设备资产线（批次 5b）：入库质检 · 调拨逐件明细 / 发货 / 逐件签收 · 资产差异 · 充电宝人工动作。
 * 钉的是与后端 QcServiceImpl / TransferOpsServiceImpl / PowerbankStateMachine 的一致性，
 * 以及「真改 db」—— 做完动作再读一次要读得回来。
 */

let seq = 0;
const uniq = () => `${Date.now()}${++seq}`;

const newCabinet = (over: Partial<Cabinet> = {}): Cabinet => {
  const c = {
    cabinetNo: `CAB-A${uniq()}`, sn: `SN-A${uniq()}`, status: "IN_STOCK", slotTotal: 8, availableCount: 0,
    vendorCode: "cd-tech", model: "X6", locationNo: null, siteNo: null, locationName: null, agentNo: null,
    onlineStatus: "OFFLINE", fwVersion: "1.0.0", lastHeartbeatAt: null, archivedAt: null, ...over,
  } as Cabinet;
  cabinets.unshift(c);
  return c;
};
const newPowerbank = (status: PowerbankStatus = "IN_STOCK"): Powerbank => {
  const p: Powerbank = {
    powerbankNo: `PB-A${uniq()}`, sn: null, vendorCode: "cd-tech", cabinetNo: "", slotIndex: null,
    battery: 90, status, health: "OK", cycles: 10, archivedAt: null,
  };
  powerbanks.unshift(p);
  return p;
};
const newTransfer = (itemType: "CABINET" | "POWERBANK") => saveInventoryTransfer({
  fromType: "WAREHOUSE", fromRef: "WH01", fromName: "迪拜一号仓",
  toType: "WAREHOUSE", toRef: "WH02", toName: "阿布扎比仓", itemType,
});

describe("入库质检", () => {
  it("机柜：检查项全过 → PASSED，结论写回设备、记录可读回", () => {
    const c = newCabinet();
    da.setQcStatus(c.cabinetNo, "PENDING");
    const r = da.inspectCabinet(c.cabinetNo, { powerOn: true, slotsOk: true });
    expect(r).toMatchObject({ itemType: "CABINET", itemNo: c.cabinetNo, result: "PASSED" });
    expect(da.qcStatusOf(c.cabinetNo)).toBe("PASSED");
    expect(da.listQcRecords(c.cabinetNo)[0].qcNo).toBe(r.qcNo);
  });

  it("★ 检查项不过时判不通过必须写原因；不能把「不过」判成过", () => {
    const c = newCabinet();
    expect(() => da.inspectCabinet(c.cabinetNo, { powerOn: true, slotsOk: false })).toThrowError(/原因/);
    expect(() => da.inspectCabinet(c.cabinetNo, { powerOn: true, slotsOk: false, result: "PASSED" })).toThrowError(/不能判通过/);
    expect(da.inspectCabinet(c.cabinetNo, { powerOn: true, slotsOk: false, note: "3 号仓门卡" }).result).toBe("FAILED");
    expect(da.qcCleared(c.cabinetNo)).toBe(false);
  });

  it("人可以把「过了」判成不过（带原因）", () => {
    const c = newCabinet();
    expect(da.inspectCabinet(c.cabinetNo, { powerOn: true, slotsOk: true, result: "FAILED", note: "外壳裂" }).result).toBe("FAILED");
  });

  it("只在在库时质检：已布放的设备出问题走故障维修", () => {
    const c = newCabinet({ status: "DEPLOYED" });
    expect(() => da.inspectCabinet(c.cabinetNo, { powerOn: true, slotsOk: true })).toThrowError(/只有在库/);
  });

  it("充电宝：电量 ≥ 60 且循环 ≤ 500 才过；质检顺带回写电量与循环", () => {
    const p = newPowerbank();
    expect(() => da.inspectPowerbank(p.powerbankNo, { battery: 40, cycles: 10 })).toThrowError(/原因/);
    const r = da.inspectPowerbank(p.powerbankNo, { battery: 80, cycles: 120 });
    expect(r.result).toBe("PASSED");
    expect(p).toMatchObject({ battery: 80, cycles: 120 });
    expect(() => da.inspectPowerbank(p.powerbankNo, { battery: 80 })).toThrowError(/缺少检查项/);
  });
});

describe("调拨作业", () => {
  it("建单校验两端：类型必须合法、两端不能相同", () => {
    expect(() => saveInventoryTransfer({ fromType: "WAREHOUSE", fromRef: "WH01", toType: "WAREHOUSE", toRef: "WH01", itemType: "CABINET" }))
      .toThrowError(/不能相同/);
    expect(() => saveInventoryTransfer({ fromType: "SHOP" as never, fromRef: "x", toType: "SITE", toRef: "ST300", itemType: "CABINET" }))
      .toThrowError(/WAREHOUSE\/SITE\/LOCATION/);
  });

  it("写入面的 fromName / toName 落到出参的 fromLocation / toLocation（此前表单字段名错，名字永远存不进去）", () => {
    const t = newTransfer("POWERBANK");
    expect(t).toMatchObject({ fromLocation: "迪拜一号仓", toLocation: "阿布扎比仓", status: "DRAFT" });
    expect(getInventoryTransfer(t.transferNo).items).toEqual([]);
  });

  it("★ 机柜调拨全链：设定明细 → 发货（柜子转运输中）→ 签收（回在库）", () => {
    const a = newCabinet();
    const b = newCabinet();
    const t = newTransfer("CABINET");
    const d = da.setTransferItems(t.transferNo, [a.cabinetNo, b.cabinetNo, a.cabinetNo]);
    expect(d.items.map((i) => i.itemNo)).toEqual([a.cabinetNo, b.cabinetNo]);
    expect(d.transfer.powerbankCount).toBe(2);

    expect(da.shipTransfer(t.transferNo).transfer.status).toBe("IN_TRANSIT");
    expect([a.status, b.status]).toEqual(["IN_TRANSIT", "IN_TRANSIT"]);
    expect(() => da.setTransferItems(t.transferNo, [a.cabinetNo])).toThrowError(/只有草稿单/);

    const r = da.receiveTransfer(t.transferNo, [a.cabinetNo, b.cabinetNo]);
    expect(r).toMatchObject({ status: "DONE", received: 2, missing: [], extra: [], diffs: [] });
    expect([a.status, b.status]).toEqual(["IN_STOCK", "IN_STOCK"]);
    expect(getInventoryTransfer(t.transferNo).items.every((i) => i.checked)).toBe(true);
  });

  it("★ 签收不因差异而卡住：缺件 / 多件照常签收，逐件落资产差异", () => {
    const a = newPowerbank();
    const b = newPowerbank();
    const t = newTransfer("POWERBANK");
    da.setTransferItems(t.transferNo, [a.powerbankNo, b.powerbankNo]);
    da.shipTransfer(t.transferNo);
    const r = da.receiveTransfer(t.transferNo, [a.powerbankNo, "PB-STRAY"]);
    expect(r.status).toBe("DONE");
    expect(r.missing).toEqual([b.powerbankNo]);
    expect(r.extra).toEqual(["PB-STRAY"]);
    expect(r.diffs.map((d) => d.kind).sort()).toEqual(["EXTRA", "MISSING"]);
    const open = da.listAssetDiffs({ status: "OPEN", sourceRef: t.transferNo });
    expect(open.total).toBe(2);
  });

  it("不在库 / 质检没过的件装不了车；发货那一刻再核一遍", () => {
    const deployed = newCabinet({ status: "DEPLOYED" });
    const t = newTransfer("CABINET");
    expect(() => da.setTransferItems(t.transferNo, [deployed.cabinetNo])).toThrowError(/不在库/);
    const pending = newCabinet();
    da.setQcStatus(pending.cabinetNo, "PENDING");
    expect(() => da.setTransferItems(t.transferNo, [pending.cabinetNo])).toThrowError(/质检未通过/);

    const ok = newCabinet();
    da.setTransferItems(t.transferNo, [ok.cabinetNo]);
    da.setQcStatus(ok.cabinetNo, "FAILED");          // 建明细之后质检被判不过
    expect(() => da.shipTransfer(t.transferNo)).toThrowError(/质检未通过/);
    expect(inventoryTransfers.find((x) => x.transferNo === t.transferNo)!.status).toBe("DRAFT");
  });

  it("非法迁移：草稿不能签收，已完成不能再发", () => {
    const t = newTransfer("POWERBANK");
    expect(() => da.receiveTransfer(t.transferNo, ["x"])).toThrowError(/不能确认收货/);
    const p = newPowerbank();
    da.setTransferItems(t.transferNo, [p.powerbankNo]);
    da.shipTransfer(t.transferNo);
    da.receiveTransfer(t.transferNo, [p.powerbankNo]);
    expect(() => da.shipTransfer(t.transferNo)).toThrowError(/不能发出/);
  });

  it("有明细的单签收时实收不能为空（空列表多半是没扫码，而不是一件都没到）", () => {
    const p = newPowerbank();
    const t = newTransfer("POWERBANK");
    da.setTransferItems(t.transferNo, [p.powerbankNo]);
    da.shipTransfer(t.transferNo);
    expect(() => da.receiveTransfer(t.transferNo, [])).toThrowError(/不能为空/);
  });
});

describe("资产差异", () => {
  it("处理要写结论；处理过的不能再处理", () => {
    const p = newPowerbank();
    const t = newTransfer("POWERBANK");
    da.setTransferItems(t.transferNo, [p.powerbankNo]);
    da.shipTransfer(t.transferNo);
    const [d] = da.receiveTransfer(t.transferNo, ["PB-OTHER"]).diffs;
    expect(() => da.resolveAssetDiff(d.diffNo, " ")).toThrowError(/结论必填/);
    const r = da.resolveAssetDiff(d.diffNo, "司机漏装，已补发");
    expect(r).toMatchObject({ status: "RESOLVED", resolveNote: "司机漏装，已补发", resolvedBy: "admin" });
    expect(() => da.resolveAssetDiff(d.diffNo, "again")).toThrowError(/已处理/);
  });
});

describe("充电宝人工动作（与后端 PowerbankStateMachine 对齐）", () => {
  it("迁移表只含人工可触发的边，且每条都在后端状态机里", () => {
    const edges = Object.values(POWERBANK_TRANSITIONS).flatMap((t) => t.from.map((f) => `${f}->${t.to}`)).sort();
    expect(edges).toEqual([
      "FAULT->IN_STOCK", "FAULT->SCRAP", "IN_CABINET->FAULT", "IN_CABINET->SCRAP",
      "IN_STOCK->FAULT", "IN_STOCK->SCRAP", "LOST->IN_CABINET",
    ]);
  });

  it("丢失 → 找回 → 报废；报废后是终态", () => {
    const p = newPowerbank("LOST");
    expect(da.transitPowerbank(p.powerbankNo, "recover").status).toBe("IN_CABINET");
    expect(da.transitPowerbank(p.powerbankNo, "scrap").status).toBe("SCRAP");
    expect(() => da.transitPowerbank(p.powerbankNo, "repair")).toThrowError(/不能维修回仓/);
  });

  it("非法迁移不改状态：在库的宝不能「找回」", () => {
    const p = newPowerbank("IN_STOCK");
    expect(() => da.transitPowerbank(p.powerbankNo, "recover")).toThrowError(/不能找回/);
    expect(p.status).toBe("IN_STOCK");
  });
});

describe("充电宝建档（与后端 PowerbankServiceImpl 对齐）", () => {
  it("建档一律在库、SN 必填；编辑不改状态", async () => {
    const { savePowerbank } = await import("./device");
    expect(() => savePowerbank({ battery: 90 })).toThrowError(/SN/);
    const p = savePowerbank({ sn: `PBSN-T${uniq()}`, status: "RENTED" });
    expect(p.status).toBe("IN_STOCK");
    const e = savePowerbank({ powerbankNo: p.powerbankNo, status: "LOST", cycles: 3 });
    expect(e).toMatchObject({ status: "IN_STOCK", cycles: 3 });
  });
});

describe("疑似丢失核实（后端 V113）", () => {
  // 「标记不是状态」：宝在被怀疑期间仍然是 RENTED，订单 / 分润 / 告警口径都不变
  const suspected = () => powerbanks.find((p) => p.suspectedLostAt && p.status === "RENTED")!;

  it("种子里有疑似丢失的样本（没有的话下面几条测的是空气）", () => {
    expect(suspected()).toBeTruthy();
    expect(suspected().status).toBe("RENTED");
  });

  it("确认丢失 → 转 LOST、摘掉柜位、清标记", () => {
    const p = suspected();
    const r = da.confirmPowerbankLost(p.powerbankNo);
    expect(r.status).toBe("LOST");
    expect(r.cabinetNo).toBeNull();
    expect(r.suspectedLostAt).toBeNull();
  });

  it("没被怀疑过的宝不能确认丢失——判断依据不在系统里就该先查清", () => {
    const normal = powerbanks.find((p) => !p.suspectedLostAt)!;
    expect(() => da.confirmPowerbankLost(normal.powerbankNo)).toThrow();
  });

  it("已找回：说明必填，且清标记后状态不变（仍是借出中）", () => {
    const p = suspected();
    expect(() => da.dismissPowerbankLost(p.powerbankNo, "  ")).toThrow();
    const r = da.dismissPowerbankLost(p.powerbankNo, "在仓库角落找到");
    expect(r.suspectedLostAt).toBeNull();
    expect(r.status).toBe("RENTED");
  });
});
