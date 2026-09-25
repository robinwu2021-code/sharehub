import { describe, it, expect } from "vitest";
import { mockApi } from "../../api/mock";

/**
 * 拓展归因：商机能记到伙伴头上（ADR-027 §五 / TDD-A2 第 3 批）。
 *
 * 盯的是**不报错、只安静算错钱**的那几件事：没签就先把佣金依据写出去 ·
 * 签了却没落成责任行 · 每存一次多记一份佣金 · 归因失败把商机本身也存不上。
 */
/**
 * 每条用例一个场地名：新建商机会**查重**（同场地名 90 天内别人在跟就拒），
 * 共用一个名字的话，第二条用例建员工商机时会撞上第一条的伙伴商机（后端同款测试用纳秒后缀）。
 */
let venueSeq = 0;
const uniqVenue = () => `归因测试场地 ${Date.now()}-${venueSeq++}`;

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
      venueName: uniqVenue(), stage: "SIGNED", ownerType: "AGENT", owner: PARTNER, siteNo: SITE,
    });
    expect(await develops(), "签下且归属伙伴 = 拓展佣金的依据，必须落成责任行").toHaveLength(1);
    await clean();
  });

  it("还在谈的商机不写责任行", async () => {
    // 没谈成也分钱，是反向的静默错账
    await clean();
    await mockApi.saveLead({
      venueName: uniqVenue(), stage: "NEGOTIATING", ownerType: "AGENT", owner: PARTNER, siteNo: SITE,
    });
    expect(await develops()).toHaveLength(0);
  });

  it("员工归属不产生伙伴拓展责任", async () => {
    // 自己人谈下来的不付对外佣金。归属类型判错的后果是白付一笔。
    await clean();
    await mockApi.saveLead({
      venueName: uniqVenue(), stage: "SIGNED", ownerType: "STAFF", owner: "BD-Layla", siteNo: SITE,
    });
    expect(await develops()).toHaveLength(0);
  });

  it("先签后建站：站点补填上去的那一次会补写", async () => {
    // 签的那一刻还没有站点可挂。条件若写成「阶段翻成 SIGNED 的那一刻」，
    // 这一类商机的佣金依据永远不会被写出来。
    await clean();
    const l = await mockApi.saveLead({
      venueName: uniqVenue(), stage: "SIGNED", ownerType: "AGENT", owner: PARTNER,
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
      venueName: uniqVenue(), stage: "SIGNED", ownerType: "AGENT", owner: PARTNER, siteNo: SITE,
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
      venueName: uniqVenue(), stage: "SIGNED", ownerType: "AGENT", owner: PARTNER, siteNo: SITE,
    });
    expect(l.leadNo, "归因写不成，商机本身仍要存下来").toBeTruthy();
    expect(await develops(), "互斥的责任不该被写进去").toHaveLength(0);
    await mockApi.removeSiteAgent(SITE, refer.id!);
  });
});

/**
 * 合同签约 → 一次性牵线费（ADR-027 §四 / TDD-A2 第 4 批）。
 *
 * 牵线的对价是「把关系介绍过来」这个一次性动作 —— 按逐单比例付会变成「介绍一次、分十年」。
 */
describe("签约付牵线费", () => {
  const SITE = "ST310";
  const REFERRER = "AG006";

  const clean = async () => {
    for (const r of await mockApi.listSiteAgents(SITE)) {
      if (r.agentNo === REFERRER) await mockApi.removeSiteAgent(SITE, r.id!);
    }
  };
  const contract = (status: "ACTIVE" | "EXPIRED") => ({
    venueNo: "VEN300", siteNo: SITE, venueName: "牵线费测试", siteName: "牵线费测试站点",
    shareRate: 0.2, entryFee: 1000, startAt: "2026-01-01", endAt: "2027-01-01",
    status, attachments: [],
  });
  const feesOf = async (contractNo: string) =>
    (await mockApi.listShareRecords({ page: 1, size: 50, keyword: contractNo })).list;

  it("签约结出牵线费，金额取责任行上的一次性对价", async () => {
    await clean();
    await mockApi.saveSiteAgent(SITE, { agentNo: REFERRER, role: "REFER", oneOffAmount: 500 });
    const c = await mockApi.saveContract(contract("ACTIVE"));
    const fees = await feesOf(c.contractNo);
    expect(fees).toHaveLength(1);
    expect(fees[0].basis).toBe("REFER");
    expect(fees[0].amount, "不乘任何基数").toBe(500);
    expect(fees[0].orderNo, "来源单据是合同本身").toBe(c.contractNo);
    await clean();
  });

  it("重复保存不重复付", async () => {
    // 合同会被反复编辑（补附件、改到期日）。每存一次付一次，就是每改一次多付一笔。
    await clean();
    await mockApi.saveSiteAgent(SITE, { agentNo: REFERRER, role: "REFER", oneOffAmount: 500 });
    const c = await mockApi.saveContract(contract("ACTIVE"));
    await mockApi.saveContract({ ...c, entryFee: 1200 });
    expect(await feesOf(c.contractNo)).toHaveLength(1);
    await clean();
  });

  it("没配金额时不落 0 元明细", async () => {
    // 没配 ≠ 0。0 元明细会让运营以为这笔本来就不该有。
    await clean();
    await mockApi.saveSiteAgent(SITE, { agentNo: REFERRER, role: "REFER" });
    const c = await mockApi.saveContract(contract("ACTIVE"));
    expect(await feesOf(c.contractNo)).toHaveLength(0);
    await clean();
  });

  it("已到期的历史合同不触发付款", async () => {
    await clean();
    await mockApi.saveSiteAgent(SITE, { agentNo: REFERRER, role: "REFER", oneOffAmount: 500 });
    const c = await mockApi.saveContract(contract("EXPIRED"));
    expect(await feesOf(c.contractNo)).toHaveLength(0);
    await clean();
  });
});
