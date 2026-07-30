// 设备/点位划拨（S1）的落库与守卫测试。
//
// 这个 tab 从前是只读列表，「划拨」两个字点不动。补上写操作后最容易退化的是两件事：
//  ① 动作看起来成功了但资产归属没变（汇总数字纹丝不动）——本文件直接断言 cabinets/sites 上的 agentNo；
//  ② 守卫形同虚设（划给不存在/已停用的代理、重复划、回收直营资产都放行）。
import { describe, expect, it } from "vitest";
import {
  agents, agentAssignmentRecords, assignAgentAssets, reclaimAgentAssets,
  listAgentAssignments, listAssignableAssets, listAgentAssignmentRecords, AgentAssignError,
} from "./agent";
import { cabinets } from "./device";
import { sites } from "./location";

const cabOf = (no: string) => cabinets.find((c) => c.cabinetNo === no)!;
const siteOf = (no: string) => sites.find((s) => s.siteNo === no)!;
const assignRowOf = (agentNo: string) =>
  listAgentAssignments({ page: 1, size: 100 }).list.find((x) => x.agentNo === agentNo)!;

/** 取一台**不属于** target 的在用机柜（划拨的合法输入）。 */
const someCabinetNotOf = (agentNo: string) => cabinets.find((c) => !c.archivedAt && c.agentNo !== agentNo)!;
const someSiteNotOf = (agentNo: string) => sites.find((s) => !s.archivedAt && s.agentNo !== agentNo)!;

describe("划拨：资产归属真的变了", () => {
  it("划拨后机柜/站点的 agentNo 落到目标代理，汇总设备数/点位数同步变化", () => {
    const target = agents.find((a) => a.status === "ENABLED" && !a.archivedAt)!;
    const cab = someCabinetNotOf(target.agentNo);
    const site = someSiteNotOf(target.agentNo);
    const before = assignRowOf(target.agentNo);
    const beforeCab = before.cabinetCount;
    const beforeSite = before.siteCount;

    const recs = assignAgentAssets({
      agentNo: target.agentNo, cabinetNos: [cab.cabinetNo], siteNos: [site.siteNo], operatorName: "tester",
    });

    // ① 资产上的归属字段真的改了（这是唯一数据源）
    expect(cabOf(cab.cabinetNo).agentNo).toBe(target.agentNo);
    expect(siteOf(site.siteNo).agentNo).toBe(target.agentNo);
    // ② 汇总是反算出来的，所以当场 +1
    const after = assignRowOf(target.agentNo);
    expect(after.cabinetCount).toBe(beforeCab + 1);
    expect(after.siteCount).toBe(beforeSite + 1);
    // ③ 落了两条 ASSIGN 流水，操作人可追溯
    expect(recs).toHaveLength(2);
    expect(recs.every((r) => r.action === "ASSIGN" && r.operatorName === "tester")).toBe(true);
    expect(agentAssignmentRecords[0].assignmentNo).toBe(recs[1].assignmentNo);
  });

  it("回收后资产回到平台直营（agentNo = null），流水记的是收回前的归属方", () => {
    const target = agents.find((a) => a.status === "ENABLED" && !a.archivedAt)!;
    const cab = someCabinetNotOf(target.agentNo);
    assignAgentAssets({ agentNo: target.agentNo, cabinetNos: [cab.cabinetNo], siteNos: [] });
    const before = assignRowOf(target.agentNo).cabinetCount;

    const recs = reclaimAgentAssets({ cabinetNos: [cab.cabinetNo], siteNos: [], operatorName: "tester" });

    expect(cabOf(cab.cabinetNo).agentNo).toBeNull();
    expect(assignRowOf(target.agentNo).cabinetCount).toBe(before - 1);
    expect(recs[0]).toMatchObject({ action: "RECLAIM", agentNo: target.agentNo, assetType: "CABINET" });
  });

  it("候选池：划拨时排除目标代理已有的资产，回收时只列该代理名下的", () => {
    const target = agents.find((a) => a.status === "ENABLED" && !a.archivedAt)!;
    const cab = someCabinetNotOf(target.agentNo);
    assignAgentAssets({ agentNo: target.agentNo, cabinetNos: [cab.cabinetNo], siteNos: [] });

    const assignable = listAssignableAssets({ excludeAgentNo: target.agentNo }).list;
    expect(assignable.some((a) => a.assetNo === cab.cabinetNo)).toBe(false);
    expect(assignable.every((a) => a.currentAgentNo !== target.agentNo)).toBe(true);

    const reclaimable = listAssignableAssets({ agentNo: target.agentNo }).list;
    expect(reclaimable.some((a) => a.assetNo === cab.cabinetNo)).toBe(true);
    expect(reclaimable.every((a) => a.currentAgentNo === target.agentNo)).toBe(true);
  });

  it("流水可按代理筛（审计入口）", () => {
    const target = agents.find((a) => a.status === "ENABLED" && !a.archivedAt)!;
    const rows = listAgentAssignmentRecords({ agentNo: target.agentNo, size: 100 }).list;
    expect(rows.length).toBeGreaterThan(0);
    expect(rows.every((r) => r.agentNo === target.agentNo)).toBe(true);
  });
});

describe("划拨守卫：非法输入一律拒绝，不做半成功", () => {
  const enabled = () => agents.find((a) => a.status === "ENABLED" && !a.archivedAt)!;

  it("目标代理不存在 / 已停用 → 拒绝", () => {
    expect(() => assignAgentAssets({ agentNo: "AG999", cabinetNos: ["CAB1001"], siteNos: [] }))
      .toThrow(AgentAssignError);
    const suspended = agents.find((a) => a.status === "SUSPENDED");
    if (suspended) {
      expect(() => assignAgentAssets({ agentNo: suspended.agentNo, cabinetNos: ["CAB1001"], siteNos: [] }))
        .toThrow(/已停用/);
    }
  });

  it("一台都没选 → 拒绝", () => {
    expect(() => assignAgentAssets({ agentNo: enabled().agentNo, cabinetNos: [], siteNos: [] }))
      .toThrow(/至少选择/);
    expect(() => reclaimAgentAssets({ cabinetNos: [], siteNos: [] })).toThrow(/至少选择/);
  });

  it("资产不存在 → 整批拒绝，前面的合法项也不落库", () => {
    const target = enabled();
    const good = someCabinetNotOf(target.agentNo);
    expect(() => assignAgentAssets({ agentNo: target.agentNo, cabinetNos: [good.cabinetNo, "CAB9999"], siteNos: [] }))
      .toThrow(/CAB9999/);
    expect(cabOf(good.cabinetNo).agentNo).not.toBe(target.agentNo);
  });

  it("重复划拨给同一代理 → 拒绝（避免流水里堆无意义的重复记录）", () => {
    const target = enabled();
    const cab = someCabinetNotOf(target.agentNo);
    assignAgentAssets({ agentNo: target.agentNo, cabinetNos: [cab.cabinetNo], siteNos: [] });
    expect(() => assignAgentAssets({ agentNo: target.agentNo, cabinetNos: [cab.cabinetNo], siteNos: [] }))
      .toThrow(/已归属/);
  });

  it("回收本就直营的资产 → 拒绝", () => {
    const target = enabled();
    const cab = someCabinetNotOf(target.agentNo);
    assignAgentAssets({ agentNo: target.agentNo, cabinetNos: [cab.cabinetNo], siteNos: [] });
    reclaimAgentAssets({ cabinetNos: [cab.cabinetNo], siteNos: [] });
    expect(() => reclaimAgentAssets({ cabinetNos: [cab.cabinetNo], siteNos: [] })).toThrow(/直营/);
  });
});
