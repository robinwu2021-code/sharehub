import { describe, it, expect } from "vitest";
import { mockApi } from "../../api/mock";

/**
 * 拓展归因：商机能记到伙伴头上（ADR-027 §五 / TDD-A2 第 3 批）。
 *
 * 盯的是**不报错、只安静算错钱**的那几件事：没签就先把佣金依据写出去 ·
 * 签了却没落成责任行 · 每存一次多记一份佣金 · 归因失败把商机本身也存不上。
 */
describe("商机拓展归因", () => {
  const SITE = "ST309";
  const PARTNER = "AG004";

  const clean = async () => {
    for (const r of await mockApi.listSiteAgents(SITE)) {
      if (r.agentNo === PARTNER && r.role === "DEVELOP") await mockApi.removeSiteAgent(SITE, r.id!);
    }
  };
  const develops = async () =>
    (await mockApi.listSiteAgents(SITE)).filter((r) => r.agentNo === PARTNER && r.role === "DEVELOP");

  it("签下且归属伙伴 → 落成一行「拓展」责任", async () => {
    await clean();
    await mockApi.saveLead({
      venueName: "归因测试场地", stage: "SIGNED", ownerType: "AGENT", owner: PARTNER, siteNo: SITE,
    });
    expect(await develops(), "签下且归属伙伴 = 拓展佣金的依据，必须落成责任行").toHaveLength(1);
    await clean();
  });

  it("还在谈的商机不写责任行", async () => {
    // 没谈成也分钱，是反向的静默错账
    await clean();
    await mockApi.saveLead({
      venueName: "归因测试场地", stage: "NEGOTIATING", ownerType: "AGENT", owner: PARTNER, siteNo: SITE,
    });
    expect(await develops()).toHaveLength(0);
  });

  it("员工归属不产生伙伴拓展责任", async () => {
    // 自己人谈下来的不付对外佣金。归属类型判错的后果是白付一笔。
    await clean();
    await mockApi.saveLead({
      venueName: "归因测试场地", stage: "SIGNED", ownerType: "STAFF", owner: "BD-Layla", siteNo: SITE,
    });
    expect(await develops()).toHaveLength(0);
  });

  it("先签后建站：站点补填上去的那一次会补写", async () => {
    // 签的那一刻还没有站点可挂。条件若写成「阶段翻成 SIGNED 的那一刻」，
    // 这一类商机的佣金依据永远不会被写出来。
    await clean();
    const l = await mockApi.saveLead({
      venueName: "归因测试场地", stage: "SIGNED", ownerType: "AGENT", owner: PARTNER,
    });
    expect(await develops(), "前提：还没指定站点时不写").toHaveLength(0);

    await mockApi.saveLead({ ...l, siteNo: SITE });
    expect(await develops(), "站点补填上去要补写").toHaveLength(1);
    await clean();
  });

  it("重复保存不多出责任行", async () => {
    // 商机会被反复编辑。每存一次多一行，就是每存一次多付一份佣金。
    await clean();
    const l = await mockApi.saveLead({
      venueName: "归因测试场地", stage: "SIGNED", ownerType: "AGENT", owner: PARTNER, siteNo: SITE,
    });
    await mockApi.saveLead({ ...l });
    expect(await develops()).toHaveLength(1);
    await clean();
  });

  it("责任冲突时商机仍然存得下来", async () => {
    // 「牵线」与「拓展」互斥。冲突要让运营看见并自己决定，
    // 但不能把商机也存不上 —— BD 会以为没存上而重填一遍。
    await clean();
    const refer = await mockApi.saveSiteAgent(SITE, { agentNo: PARTNER, role: "REFER" });
    const l = await mockApi.saveLead({
      venueName: "归因测试场地", stage: "SIGNED", ownerType: "AGENT", owner: PARTNER, siteNo: SITE,
    });
    expect(l.leadNo, "归因写不成，商机本身仍要存下来").toBeTruthy();
    expect(await develops(), "互斥的责任不该被写进去").toHaveLength(0);
    await mockApi.removeSiteAgent(SITE, refer.id!);
  });
});
