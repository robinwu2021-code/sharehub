// 整站换商家（原「设备/点位划拨」）的落库与守卫测试。
//
// 2026-10-28 起划拨只剩站点粒度（后端 620656d9：CABINET / LOCATION → 400 granularity_retired）：
// 机柜随站点走，单台设备换位置走出库池的绑定 / 解绑或撤机。
// 最容易退化的两件事：
//  ① 只改了站点、没改站上柜子与 OPERATE 责任行 ——「看得到」与「分得到」就分成两个商家
//    （docs/technical/设备绑定与商家归属-现状分析.md §四.1）；
//  ② 守卫形同虚设（划给不存在/已停用的商家、重复划、回收直营站点、按机柜划都放行）。
import { describe, expect, it } from "vitest";
import {
  agents, agentAssignmentRecords, assignAgentAssets, reclaimAgentAssets, listSiteAgents,
  listAgentAssignments, listAssignableAssets, listAgentAssignmentRecords, AgentAssignError,
} from "./agent";
import { cabinets } from "./device";
import { sites, locations } from "./location";

const siteOf = (no: string) => sites.find((s) => s.siteNo === no)!;
const cabsOnSite = (siteNo: string) => {
  const locs = new Set(locations.filter((l) => l.siteNo === siteNo).map((l) => l.locationNo));
  return cabinets.filter((c) => c.locationNo && locs.has(c.locationNo));
};
const assignRowOf = (agentNo: string) =>
  listAgentAssignments({ page: 1, size: 100 }).list.find((x) => x.agentNo === agentNo)!;
/** 一个**不属于** target、站上有柜子的站点（划拨的合法输入）。 */
const someSiteNotOf = (agentNo: string) =>
  sites.find((s) => !s.archivedAt && s.agentNo !== agentNo && cabsOnSite(s.siteNo).length > 0)!;
const enabled = () => agents.find((a) => a.status === "ENABLED" && !a.archivedAt)!;

describe("整站换商家：站点、柜子、责任行一起变", () => {
  it("站点、站上柜子的商家与出库池归属、OPERATE 责任行同时落到目标商家", () => {
    const target = enabled();
    const site = someSiteNotOf(target.agentNo);
    const beforeSites = assignRowOf(target.agentNo).siteCount;

    const recs = assignAgentAssets({ agentNo: target.agentNo, cabinetNos: [], siteNos: [site.siteNo], operatorName: "tester" });

    expect(siteOf(site.siteNo).agentNo).toBe(target.agentNo);
    for (const c of cabsOnSite(site.siteNo)) {
      expect(c.agentNo).toBe(target.agentNo);
      expect(c.issuedAgentNo).toBe(target.agentNo);
    }
    const operate = listSiteAgents(site.siteNo).filter((r) => r.role === "OPERATE");
    expect(operate.map((r) => r.agentNo)).toEqual([target.agentNo]);
    expect(assignRowOf(target.agentNo).siteCount).toBe(beforeSites + 1);
    expect(recs).toHaveLength(1);
    expect(recs[0]).toMatchObject({ action: "ASSIGN", assetType: "SITE", operatorName: "tester" });
    expect(agentAssignmentRecords[0].assignmentNo).toBe(recs[0].assignmentNo);
  });

  it("收回平台直营：站点、柜子的商家与出库池归属清空，OPERATE 责任行删掉；流水记收回前的商家", () => {
    const target = enabled();
    const site = someSiteNotOf(target.agentNo);
    assignAgentAssets({ agentNo: target.agentNo, cabinetNos: [], siteNos: [site.siteNo] });

    const recs = reclaimAgentAssets({ cabinetNos: [], siteNos: [site.siteNo], operatorName: "tester" });

    expect(siteOf(site.siteNo).agentNo).toBeNull();
    for (const c of cabsOnSite(site.siteNo)) expect(c).toMatchObject({ agentNo: null, issuedAgentNo: null });
    expect(listSiteAgents(site.siteNo).some((r) => r.role === "OPERATE")).toBe(false);
    expect(recs[0]).toMatchObject({ action: "RECLAIM", agentNo: target.agentNo, assetType: "SITE" });
  });

  it("候选池：划拨时排除目标商家已有的站点，回收时只列该商家名下的", () => {
    const target = enabled();
    const site = someSiteNotOf(target.agentNo);
    assignAgentAssets({ agentNo: target.agentNo, cabinetNos: [], siteNos: [site.siteNo] });

    const assignable = listAssignableAssets({ excludeAgentNo: target.agentNo }).list;
    expect(assignable.some((a) => a.assetNo === site.siteNo)).toBe(false);
    expect(assignable.every((a) => a.currentAgentNo !== target.agentNo)).toBe(true);

    const reclaimable = listAssignableAssets({ agentNo: target.agentNo }).list;
    expect(reclaimable.some((a) => a.assetNo === site.siteNo)).toBe(true);
    expect(reclaimable.every((a) => a.currentAgentNo === target.agentNo)).toBe(true);
  });

  it("流水可按商家筛（审计入口）", () => {
    const target = enabled();
    const rows = listAgentAssignmentRecords({ agentNo: target.agentNo, size: 100 }).list;
    expect(rows.length).toBeGreaterThan(0);
    expect(rows.every((r) => r.agentNo === target.agentNo)).toBe(true);
  });
});

describe("整站换商家守卫：非法输入一律拒绝，不做半成功", () => {
  it("按机柜划拨 / 回收已下线", () => {
    expect(() => assignAgentAssets({ agentNo: enabled().agentNo, cabinetNos: ["CAB1001"], siteNos: [] }))
      .toThrow(/按机柜划拨已下线/);
    expect(() => reclaimAgentAssets({ cabinetNos: ["CAB1001"], siteNos: [] })).toThrow(/按机柜回收已下线/);
  });

  it("目标商家不存在 / 已停用 → 拒绝", () => {
    expect(() => assignAgentAssets({ agentNo: "AG999", cabinetNos: [], siteNos: ["ST300"] })).toThrow(AgentAssignError);
    const suspended = agents.find((a) => a.status === "SUSPENDED");
    if (suspended) {
      expect(() => assignAgentAssets({ agentNo: suspended.agentNo, cabinetNos: [], siteNos: ["ST300"] })).toThrow(/已停用/);
    }
  });

  it("一个站点都没选 → 拒绝", () => {
    expect(() => assignAgentAssets({ agentNo: enabled().agentNo, cabinetNos: [], siteNos: [] })).toThrow(/至少选择/);
    expect(() => reclaimAgentAssets({ cabinetNos: [], siteNos: [] })).toThrow(/至少选择/);
  });

  it("站点不存在 → 整批拒绝，前面的合法项也不落库", () => {
    const target = enabled();
    const good = someSiteNotOf(target.agentNo);
    const before = good.agentNo;
    expect(() => assignAgentAssets({ agentNo: target.agentNo, cabinetNos: [], siteNos: [good.siteNo, "ST9999"] }))
      .toThrow(/ST9999/);
    expect(siteOf(good.siteNo).agentNo).toBe(before);
  });

  it("重复划给同一商家 → 拒绝（避免流水里堆无意义的重复记录）", () => {
    const target = enabled();
    const site = someSiteNotOf(target.agentNo);
    assignAgentAssets({ agentNo: target.agentNo, cabinetNos: [], siteNos: [site.siteNo] });
    expect(() => assignAgentAssets({ agentNo: target.agentNo, cabinetNos: [], siteNos: [site.siteNo] })).toThrow(/已归属/);
  });

  it("回收本就直营的站点 → 拒绝", () => {
    const target = enabled();
    const site = someSiteNotOf(target.agentNo);
    assignAgentAssets({ agentNo: target.agentNo, cabinetNos: [], siteNos: [site.siteNo] });
    reclaimAgentAssets({ cabinetNos: [], siteNos: [site.siteNo] });
    expect(() => reclaimAgentAssets({ cabinetNos: [], siteNos: [site.siteNo] })).toThrow(/直营/);
  });
});
