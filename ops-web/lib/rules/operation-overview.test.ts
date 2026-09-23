import { describe, it, expect } from "vitest";
import { attentionOf, buildOverview, buildSiteStats, siteNoOfOrder, cabinetOnline, type OverviewInput } from "./operation-overview";
import type { Site, SitePoint, Contract } from "../types/location";
import type { Cabinet } from "../types/device";
import type { RentOrder } from "../types/order";

const now = new Date("2026-09-22T12:00:00Z");

const site = (no: string, x: Partial<Site> = {}): Site => ({
  siteNo: no, name: `站点${no}`, venueName: "场地方", agentNo: null, regionId: "DU", regionName: "迪拜",
  address: "addr", lat: 25, lng: 55, sceneType: "商场", pointCount: 0, cabinetCount: 0, status: "ACTIVE",
  archivedAt: null, ...x,
});
const point = (no: string, siteNo: string): SitePoint => ({
  locationNo: no, name: `点位${no}`, siteNo, siteName: `站点${siteNo}`, spotDesc: "", cabinetCount: 0,
  status: "ACTIVE", archivedAt: null,
});
const cab = (no: string, locationNo: string | null, x: Partial<Cabinet> = {}): Cabinet => ({
  cabinetNo: no, sn: no, vendorCode: "V", model: "M", locationNo, siteNo: null, agentNo: null,
  onlineStatus: "ONLINE", status: "IN_SERVICE", slotTotal: 8, availableCount: 4, fwVersion: "1.0",
  lastHeartbeatAt: now.toISOString(), archivedAt: null, ...x,
} as Cabinet);
const order = (no: string, cabinetNo: string, fee: number, endAt: string, durationMin = 60): RentOrder => ({
  orderNo: no, cUserNo: "U", cabinetNo, returnCabinetNo: null, powerbankNo: null, status: "SETTLED",
  rentStartAt: endAt, rentEndAt: endAt, durationMin, feeAmount: fee, depositAmount: 0, currency: "AED",
} as RentOrder);
const contract = (no: string, siteName: string, endAt: string): Contract => ({
  contractNo: no, venueName: "场地方", siteName, shareRate: 0.2, entryFee: 0,
  startAt: "2026-01-01", endAt, status: "ACTIVE", attachments: [],
});

/** 一个「一切正常」的基底：1 个站点、1 个点位、1 台在线机柜、近 7 日有单、有合同/分成/方案。 */
function baseInput(over: Partial<OverviewInput> = {}): OverviewInput {
  const s = site("ST1");
  return {
    sites: [s], points: [point("LOC1", "ST1")], cabinets: [cab("CB1", "LOC1")], powerbanks: [],
    orders: [order("O1", "CB1", 10, "2026-09-21T10:00:00Z")],
    contracts: [contract("CT1", s.name, "2027-01-01")],
    sharedSiteNames: new Set([s.name]), pricedSiteNos: new Set(["ST1"]),
    from: new Date("2026-09-16T00:00:00Z"), to: new Date("2026-09-22T23:59:59Z"), now, currency: "AED",
    ...over,
  };
}

describe("订单归属站点：机柜 → 点位 → 站点", () => {
  it("机柜自带 siteNo 时直接用；没有则按点位反查；机柜不存在时无归属", () => {
    const input = baseInput();
    expect(siteNoOfOrder(order("O", "CB1", 1, "x"), input.cabinets, input.points)).toBe("ST1");
    const withSite = [cab("CB9", null, { siteNo: "ST9" })];
    expect(siteNoOfOrder(order("O", "CB9", 1, "x"), withSite, input.points)).toBe("ST9");
    expect(siteNoOfOrder(order("O", "NOPE", 1, "x"), input.cabinets, input.points)).toBeUndefined();
  });
});

describe("在线率口径：在线且非故障", () => {
  it("故障的机柜即使 ONLINE 也不算在线", () => {
    expect(cabinetOnline(cab("A", null))).toBe(true);
    expect(cabinetOnline(cab("A", null, { status: "FAULT" }))).toBe(false);
    expect(cabinetOnline(cab("A", null, { onlineStatus: "OFFLINE" }))).toBe(false);
  });
});

describe("待关注站点的 6 条规则", () => {
  const kinds = (input: OverviewInput) => attentionOf(input.sites[0], input).map((a) => a.kind);

  it("基底一切正常时不产生任何待关注项", () => {
    expect(kinds(baseInput())).toEqual([]);
  });

  it("有机柜但全部离线 ≥2 小时 → 高", () => {
    const input = baseInput({
      cabinets: [cab("CB1", "LOC1", { onlineStatus: "OFFLINE", lastHeartbeatAt: "2026-09-22T07:00:00Z" })],
    });
    const item = attentionOf(input.sites[0], input).find((a) => a.kind === "ALL_OFFLINE")!;
    expect(item.severity).toBe("high");
    expect(item.detail).toContain("5 小时");
  });

  it("刚离线不到 2 小时不报（避免心跳抖动刷屏）", () => {
    const input = baseInput({
      cabinets: [cab("CB1", "LOC1", { onlineStatus: "OFFLINE", lastHeartbeatAt: "2026-09-22T11:30:00Z" })],
    });
    expect(kinds(input)).not.toContain("ALL_OFFLINE");
  });

  it("营业中但没有机柜 → 中；暂停营业的站点不报这一条", () => {
    expect(kinds(baseInput({ cabinets: [] }))).toContain("NO_CABINET");
    const paused = baseInput({ cabinets: [], sites: [site("ST1", { status: "PAUSED" })] });
    expect(kinds(paused)).not.toContain("NO_CABINET");
  });

  it("合同已过期且仍在营业 → 高；30 天内到期 → 中；两者只报更严重的那条", () => {
    const expired = baseInput({ contracts: [contract("CT1", "站点ST1", "2026-09-01")] });
    expect(kinds(expired)).toContain("CONTRACT_EXPIRED");
    expect(kinds(expired)).not.toContain("CONTRACT_SOON");
    const soon = baseInput({ contracts: [contract("CT1", "站点ST1", "2026-10-10")] });
    expect(kinds(soon)).toContain("CONTRACT_SOON");
  });

  it("没有收费方案、没有分成配置各报一条", () => {
    expect(kinds(baseInput({ pricedSiteNos: new Set() }))).toContain("NO_PRICE_PLAN");
    expect(kinds(baseInput({ sharedSiteNames: new Set() }))).toContain("NO_SHARING");
  });

  it("近 7 日零订单 → 低；没有机柜时不报（已经由 NO_CABINET 说明）", () => {
    expect(kinds(baseInput({ orders: [] }))).toContain("NO_ORDER");
    const old = baseInput({ orders: [order("O1", "CB1", 10, "2026-09-01T10:00:00Z")] });
    expect(kinds(old)).toContain("NO_ORDER");
    expect(kinds(baseInput({ cabinets: [], orders: [] }))).not.toContain("NO_ORDER");
  });
});

describe("概览聚合", () => {
  it("规模、经营、排行、趋势、场景分布", () => {
    const input = baseInput({
      sites: [site("ST1"), site("ST2", { sceneType: "机场", status: "PAUSED" })],
      points: [point("LOC1", "ST1"), point("LOC2", "ST2")],
      cabinets: [cab("CB1", "LOC1"), cab("CB2", "LOC2", { onlineStatus: "OFFLINE" })],
      orders: [
        order("O1", "CB1", 10, "2026-09-21T10:00:00Z"),
        order("O2", "CB1", 20, "2026-09-21T11:00:00Z"),
        order("O3", "CB2", 5, "2026-09-22T09:00:00Z"),
        order("OLD", "CB1", 99, "2026-08-01T09:00:00Z"), // 区间外不计
      ],
    });
    const o = buildOverview(input);
    expect(o.scale).toMatchObject({ siteTotal: 2, siteActive: 1, sitePaused: 1, pointTotal: 2, cabinetTotal: 2, cabinetOnline: 1, onlineRate: 0.5 });
    expect(o.business).toMatchObject({ orders: 3, gmv: 35, avgOrderValue: 11.67 });
    expect(o.ranking[0]).toMatchObject({ siteNo: "ST1", gmv: 30, orders: 2 });
    expect(o.ranking[1]).toMatchObject({ siteNo: "ST2", gmv: 5, orders: 1 });
    // 趋势覆盖整个区间，按市场时区分天
    expect(o.trend).toHaveLength(7);
    expect(o.trend.find((t) => t.day === "2026-09-21")).toMatchObject({ orders: 2, gmv: 30 });
    expect(o.scenes.map((s) => s.sceneType).sort()).toEqual(["商场", "机场"]);
  });

  it("跨零点：UTC 22:30 的订单按迪拜时间落到次日", () => {
    const input = baseInput({ orders: [order("O1", "CB1", 10, "2026-09-20T22:30:00Z")] });
    const o = buildOverview(input);
    expect(o.trend.find((t) => t.day === "2026-09-21")?.orders).toBe(1);
    expect(o.trend.find((t) => t.day === "2026-09-20")?.orders).toBe(0);
  });

  it("未结束或零金额的订单不计入经营指标", () => {
    const input = baseInput({
      orders: [
        { ...order("O1", "CB1", 10, "2026-09-21T10:00:00Z"), rentEndAt: null },
        order("O2", "CB1", 0, "2026-09-21T10:00:00Z"),
      ],
    });
    expect(buildOverview(input).business.orders).toBe(0);
  });

  it("地图就绪度如实反映有经纬度的站点数", () => {
    const input = baseInput({ sites: [site("ST1"), site("ST2", { lat: 0, lng: 0 })] });
    expect(buildOverview(input).geoReady).toEqual({ withGeo: 1, total: 2 });
  });
});

describe("单站统计", () => {
  it("只统计本站订单，并按点位拆分", () => {
    const input = baseInput({
      sites: [site("ST1"), site("ST2")],
      points: [point("LOC1", "ST1"), point("LOC2", "ST1"), point("LOC3", "ST2")],
      cabinets: [cab("CB1", "LOC1"), cab("CB2", "LOC2"), cab("CB3", "LOC3")],
      orders: [
        order("O1", "CB1", 10, "2026-09-21T10:00:00Z", 30),
        order("O2", "CB2", 20, "2026-09-21T10:00:00Z", 90),
        order("O3", "CB3", 50, "2026-09-21T10:00:00Z", 60), // 别的站点，不计
      ],
    });
    const s = buildSiteStats("ST1", input);
    expect(s).toMatchObject({ orders: 2, gmv: 30, avgOrderValue: 15, avgDurationMin: 60, cabinetCount: 2 });
    expect(s.byPoint.map((p) => [p.locationNo, p.gmv])).toEqual([["LOC2", 20], ["LOC1", 10]]);
  });
});
