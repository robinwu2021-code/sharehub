// 出库池与绑定 mock 的不变式（技术设计 §二.6）与全链路。规则在 db 层强制，这里直接调 db 函数。
import { describe, it, expect, beforeAll } from "vitest";
import { cabinets } from "./device";
import { sites, locations } from "./location";
import { agents, siteAgents } from "./agent";
import { workOrders } from "./workorder";
import {
  ensurePoolSeed, issueDevices, returnDevicesToWarehouse, issuedSummary, bindDevice, bindDevices, unbindDevice,
  poolHit, POOL_WAREHOUSE,
} from "./device-pool";

const cab = (no: string) => cabinets.find((c) => c.cabinetNo === no)!;
const inWarehouse = () => cabinets.filter((c) => c.status === "IN_STOCK" && c.warehouseNo && !c.issuedAgentNo && !c.archivedAt);
const agent = (no: string) => agents.find((a) => a.agentNo === no)!;

describe("出库池与绑定", () => {
  beforeAll(() => ensurePoolSeed());

  it("种子：资料齐全的商家有默认站点 + 默认点位 + OPERATE 责任行；仓里有待出库设备", () => {
    const ag2 = agent("AG002");
    expect(ag2.defaultSiteNo).toBeTruthy();
    const dp = locations.filter((l) => l.siteNo === ag2.defaultSiteNo && l.isDefault);
    expect(dp).toHaveLength(1);
    expect(siteAgents.some((r) => r.siteNo === ag2.defaultSiteNo && r.agentNo === "AG002" && r.role === "OPERATE")).toBe(true);
    expect(inWarehouse().length).toBeGreaterThanOrEqual(4);
  });

  it("出库必须选资料齐全的商家；整批要么全成要么全不成", () => {
    const [a, b] = inWarehouse();
    expect(() => issueDevices({ agentNo: "AG009", cabinetNos: [a.cabinetNo] })).toThrow(/位置与行业/);
    // 一台合格、一台已绑定点位 → 整批被拒，合格那台也没动
    const bound = cabinets.find((c) => c.locationNo && c.status === "IN_STOCK") ?? cabinets.find((c) => c.locationNo)!;
    expect(() => issueDevices({ agentNo: "AG002", cabinetNos: [a.cabinetNo, bound.cabinetNo] })).toThrow(/整批未动/);
    expect(cab(a.cabinetNo).issuedAgentNo).toBeNull();
    // I1：出库后不在仓
    issueDevices({ agentNo: "AG002", cabinetNos: [a.cabinetNo, b.cabinetNo] });
    expect(cab(a.cabinetNo)).toMatchObject({ issuedAgentNo: "AG002", warehouseNo: null, agentNo: null, locationNo: null });
    expect(() => issueDevices({ agentNo: "AG003", cabinetNos: [a.cabinetNo] })).toThrow(/出库池/);
  });

  it("绑定不给点位 = 默认站点默认点位；商家由点位派生（I3）；可同时建装机工单", () => {
    const [no] = cabinets.filter((c) => poolHit(c, "AG002", "UNBOUND")).map((c) => c.cabinetNo);
    const before = workOrders.length;
    const r = bindDevice(no, { openInstallWo: true });
    const site = sites.find((s) => s.siteNo === agent("AG002").defaultSiteNo)!;
    expect(r.cabinet).toMatchObject({ siteNo: site.siteNo, agentNo: "AG002", issuedAgentNo: "AG002" });
    expect(locations.find((l) => l.locationNo === r.cabinet.locationNo)?.isDefault).toBe(true);
    expect(r.woNo).toBeTruthy();
    expect(workOrders.length).toBe(before + 1);
    expect(workOrders[0]).toMatchObject({ type: "INSTALL", cabinetNo: no });
  });

  it("I2：出库给 AG002 的设备不能绑到别的商家或直营站点的点位", () => {
    const [no] = cabinets.filter((c) => poolHit(c, "AG002", "UNBOUND")).map((c) => c.cabinetNo);
    const other = locations.find((l) => {
      const s = sites.find((x) => x.siteNo === l.siteNo);
      return s && s.agentNo !== "AG002" && l.status === "ACTIVE" && !l.archivedAt;
    })!;
    expect(() => bindDevice(no, { locationNo: other.locationNo })).toThrow(/出库给了 AG002/);
    expect(cab(no).locationNo).toBeNull();
  });

  it("批量绑定一个事务：混进一台不在该商家出库池的设备，整批不动", () => {
    const pool = cabinets.filter((c) => poolHit(c, "AG002", "UNBOUND")).map((c) => c.cabinetNo);
    const stranger = inWarehouse()[0].cabinetNo;
    expect(() => bindDevices({ agentNo: "AG002", deviceNos: [...pool, stranger] })).toThrow(/不在 AG002 的出库池/);
    for (const no of pool) expect(cab(no).locationNo).toBeNull();
  });

  it("解绑回到同一商家的出库池；已上线的不能解绑", () => {
    const bound = cabinets.find((c) => poolHit(c, "AG002", "BOUND") && c.status === "IN_STOCK")!;
    const back = unbindDevice(bound.cabinetNo, "换点位");
    expect(back).toMatchObject({ locationNo: null, agentNo: null, siteNo: null, issuedAgentNo: "AG002", warehouseNo: null });
    const live = cabinets.find((c) => c.status === "DEPLOYED" && c.locationNo)!;
    expect(() => unbindDevice(live.cabinetNo, "x")).toThrow(/撤机/);
  });

  it("退回仓库只收未绑定的；退回后离开出库池、回到仓里（I1）", () => {
    const pool = cabinets.filter((c) => poolHit(c, "AG002", "UNBOUND")).map((c) => c.cabinetNo);
    expect(() => returnDevicesToWarehouse({ cabinetNos: pool, reason: "" })).toThrow(/原因/);
    returnDevicesToWarehouse({ cabinetNos: [pool[0]], reason: "多发了" });
    expect(cab(pool[0])).toMatchObject({ issuedAgentNo: null, warehouseNo: POOL_WAREHOUSE });
  });

  it("汇总按商家统计未绑定 / 已绑定 / 已上线", () => {
    const rows = issuedSummary();
    const ag2 = rows.find((r) => r.agentNo === "AG002")!;
    const mine = cabinets.filter((c) => c.issuedAgentNo === "AG002" && !c.archivedAt);
    expect(ag2.unbound + ag2.bound + ag2.live).toBe(mine.length);
    expect(issuedSummary("AG002")).toHaveLength(1);
  });
});
