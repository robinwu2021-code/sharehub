// 商家核心资料与默认站点（V157）—— mock 与后端 Phase1MerchantProfileTest 同口径。
//
// 守三件不报错就会做错的事：
//  ① 位置、行业不齐也能划拨 —— 设备落在没有坐标的商家名下，「找附近」排不到；
//  ② 补齐位置不建站 / 每存一次建一个站；
//  ③ 只给地址不给坐标被静默接受 —— 站点在地图上没有落点。
import { describe, expect, it } from "vitest";
import { agentMock } from "@/lib/api/mocks/agent";
import { agents, assignAgentAssets, agentAssignmentRecords } from "./agent";
import { sites } from "./location";

const fresh = async (name: string) =>
  agentMock.saveAgent({ name, status: "ENABLED", agentType: "AGENT", regionScope: "", shareRate: 0.3 });

describe("商家核心资料", () => {
  it("★ 种子里留空的 AG009 划拨被拦，补齐后的商家不受影响", () => {
    const empty = agents.find((a) => a.agentNo === "AG009")!;
    expect(empty.address).toBeUndefined();
    expect(() => assignAgentAssets({ agentNo: "AG009", cabinetNos: [], siteNos: ["ST300"] })).toThrow(/核心资料未补齐/);
  });

  it("★★ 补齐位置即建一个默认站点并划给商家；重复保存不重复建；搬迁只同步地址", async () => {
    const a = await fresh("默认站点 mock 商家");
    expect(a.defaultSiteNo ?? null).toBeNull();

    // 种子流水里有同号的演示记录（ASG5100 · AG900 · ST900），所以只数本次新增的
    const before = agentAssignmentRecords.length;
    const saved = await agentMock.saveAgent({ ...a, address: "JBR The Walk", lng: 55.1352, lat: 25.0779, industry: "HOTEL" });
    const siteNo = saved.defaultSiteNo!;
    const site = sites.find((s) => s.siteNo === siteNo)!;
    expect(site.agentNo).toBe(a.agentNo);
    expect(site.venueNo).toBeNull();
    expect(agentAssignmentRecords.length - before).toBe(1);
    expect(agentAssignmentRecords[0]).toMatchObject({ agentNo: a.agentNo, assetType: "SITE", assetNo: siteNo, action: "ASSIGN" });

    await agentMock.saveAgent({ ...saved });
    expect(sites.filter((s) => s.agentNo === a.agentNo)).toHaveLength(1);

    const moved = await agentMock.saveAgent({ ...saved, address: "Business Bay", lng: 55.2655, lat: 25.1866 });
    expect(moved.defaultSiteNo).toBe(siteNo);
    expect(sites.find((s) => s.siteNo === siteNo)!.address).toBe("Business Bay");
  });

  it("只给地址不给坐标、行业不在词表：当场拒", async () => {
    const a = await fresh("校验 mock 商家");
    expect(() => agentMock.saveAgent({ ...a, address: "只有地址", lng: null, lat: null })).toThrow();
    expect(() => agentMock.saveAgent({ ...a, industry: "SPACESHIP" as never })).toThrow();
  });
});
