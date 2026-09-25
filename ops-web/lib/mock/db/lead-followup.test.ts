// BD 线索跟进记录（CRM 时间线）的守卫与自洽性测试。
//
// CRM 页从前只有「增改档案」：阶段能被改，但**没人知道为什么变的**。补上跟进流水后，
// 三件事必须由 db 层兜住：① 内容/方式/阶段的取值校验；② 跟进与阶段推进是同一个动作
// （改了阶段必留下解释它的那条记录）；③ 列表的「最后跟进」时间与流水首条同源。
// 另有一组种子自洽断言：种子若与线索的 stage/owner 对不上，时间线一眼就是假数据。
import { describe, expect, it } from "vitest";
import {
  leads, leadFollowUps, listLeadFollowUps, addLeadFollowUp, LeadFollowUpError,
  saveLead, claimLead, convertLead, getLead, contracts, sites, venues, listLeads,
} from "./location";
import { LEAD_STAGES, type LeadFollowChannel, type LeadStage } from "../../types";

const leadOf = (leadNo: string) => leads.find((x) => x.leadNo === leadNo)!;
const firstWithStage = (stage: LeadStage) => leads.find((x) => x.stage === stage && !x.inPool)!;

describe("种子自洽：跟进记录与线索本身对得上", () => {
  it("每条跟进都挂在真实线索上，负责人与线索负责人一致", () => {
    for (const f of leadFollowUps) {
      const lead = leads.find((x) => x.leadNo === f.leadNo);
      expect(lead, `孤儿跟进记录 ${f.followNo}`).toBeTruthy();
      // 池里的商机负责人已清空，历史跟进是前任记的
      expect(f.owner, `跟进 ${f.followNo} 的负责人与线索不符`).toBe(lead!.owner ?? lead!.prevOwner);
    }
  });

  it("阶段链首尾自洽：首条无来源阶段，末条阶段 = 线索当前阶段", () => {
    for (const lead of leads) {
      // 按时间正序（listLeadFollowUps 是倒序，这里自己排，测的是数据不是接口）
      const chain = leadFollowUps.filter((x) => x.leadNo === lead.leadNo)
        .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
      if (!chain.length) {
        // 没有跟进的线索只能停在 NEW —— 否则就是「阶段动了但没人跟进过」
        expect(lead.stage, `线索 ${lead.leadNo} 无跟进却不在 NEW`).toBe("NEW");
        continue;
      }
      expect(chain[0].fromStage).toBeNull();
      expect(chain[chain.length - 1].toStage).toBe(lead.stage);
      // 相邻两条首尾相接：上一条的 toStage 必须是下一条的 fromStage
      for (let i = 1; i < chain.length; i++) expect(chain[i].fromStage).toBe(chain[i - 1].toStage);
    }
  });

  it("线索的 updatedAt = 最后一次跟进时间（列表「最后跟进」列与时间线首条同源）", () => {
    for (const lead of leads) {
      const latest = leadFollowUps.filter((x) => x.leadNo === lead.leadNo)
        .map((x) => x.createdAt).sort().pop();
      if (latest) expect(lead.updatedAt, `线索 ${lead.leadNo}`).toBe(latest);
    }
  });

  it("已签约 / 已流失的线索不留「下次跟进」计划（终态再约人是无意义待办）", () => {
    for (const f of leadFollowUps) {
      if (f.toStage === "SIGNED" || f.toStage === "LOST") expect(f.nextAt, f.followNo).toBeNull();
    }
  });
});

describe("跟进记录守卫", () => {
  it("线索不存在直接拒绝", () => {
    expect(() => addLeadFollowUp("LD9999", { content: "x", channel: "CALL" })).toThrow(LeadFollowUpError);
  });

  it("内容必填：空白内容不产生记录", () => {
    const lead = firstWithStage("NEW");
    const n = leadFollowUps.length;
    expect(() => addLeadFollowUp(lead.leadNo, { content: "   ", channel: "CALL" })).toThrow(/必填/);
    expect(leadFollowUps.length).toBe(n);
  });

  it("跟进方式与阶段取值必须在取值域内", () => {
    const lead = firstWithStage("NEW");
    expect(() => addLeadFollowUp(lead.leadNo, { content: "x", channel: "SMS" as LeadFollowChannel })).toThrow(/方式非法/);
    expect(() => addLeadFollowUp(lead.leadNo, { content: "x", channel: "CALL", toStage: "WON" as LeadStage })).toThrow(/阶段非法/);
  });
});

describe("跟进与阶段推进是同一个动作", () => {
  it("传 toStage：线索阶段跟着变，记录里留下 from → to", () => {
    const lead = firstWithStage("CONTACTED");
    const from = lead.stage;
    const r = addLeadFollowUp(lead.leadNo, { content: "看完点位，进入谈判", channel: "VISIT", toStage: "NEGOTIATING" });

    expect(r.fromStage).toBe(from);
    expect(r.toStage).toBe("NEGOTIATING");
    expect(leadOf(lead.leadNo).stage).toBe("NEGOTIATING");
    expect(leadOf(lead.leadNo).updatedAt).toBe(r.createdAt); // 更新时间对齐本条
    expect(leadFollowUps[0].followNo).toBe(r.followNo); // 新记录置顶
  });

  it("不传 toStage：只留痕不动阶段（与后端同：from == to 即未推进）", () => {
    const lead = firstWithStage("NEW");
    const r = addLeadFollowUp(lead.leadNo, { content: "对方未接，改天再拨", channel: "CALL" });
    expect(r.fromStage).toBe(r.toStage);
    expect(r.toStage).toBe("NEW");
    expect(leadOf(lead.leadNo).stage).toBe("NEW");
  });

  it("stage 与当前相同视为不改阶段（不产生空转迁移）", () => {
    const lead = firstWithStage("SIGNED");
    const r = addLeadFollowUp(lead.leadNo, { content: "回访确认进场时间", channel: "WHATSAPP", toStage: lead.stage });
    expect(r.fromStage).toBe("SIGNED");
    expect(leadOf(lead.leadNo).stage).toBe("SIGNED");
  });

  it("★ 状态机与后端 LeadStateMachine 同严：不许跳步，LOST 只能重新激活回 NEW", () => {
    const fresh = firstWithStage("NEW");
    expect(() => addLeadFollowUp(fresh.leadNo, { content: "直接谈", channel: "CALL", toStage: "NEGOTIATING" }))
      .toThrow(/不能从 NEW 直接变为 NEGOTIATING/);
    const lost = firstWithStage("LOST");
    expect(() => addLeadFollowUp(lost.leadNo, { content: "重启谈判", channel: "CALL", toStage: "NEGOTIATING" })).toThrow();
    const r = addLeadFollowUp(lost.leadNo, { content: "对方独家到期，重新接触", channel: "CALL", toStage: "NEW" });
    expect(r.toStage).toBe("NEW");
    expect(leadOf(lost.leadNo).reactivatedAt, "重新激活要记时刻").toBeTruthy();
    expect(leadOf(lost.leadNo).lostReason, "丢单原因保留作历史").toBeTruthy();
    expect(LEAD_STAGES).toContain("NEGOTIATING");
  });

  it("推到 LOST：本条内容就是丢单原因", () => {
    const lead = firstWithStage("CONTACTED");
    addLeadFollowUp(lead.leadNo, { content: "对方选了别家", channel: "CALL", toStage: "LOST" });
    expect(leadOf(lead.leadNo)).toMatchObject({ stage: "LOST", lostReason: "对方选了别家" });
    expect(leadOf(lead.leadNo).lostAt).toBeTruthy();
  });

  it("★ 池里的商机不能跟进 —— 先认领，否则「谁在跟」又说不清", () => {
    const pooled = leads.find((x) => x.inPool)!;
    expect(() => addLeadFollowUp(pooled.leadNo, { content: "x", channel: "CALL" })).toThrow(/先认领/);
  });
});

describe("列表：按线索收敛且新的在前", () => {
  it("只返回该线索的记录，倒序，可按内容/负责人搜", () => {
    const lead = leads.find((x) => x.stage === "NEGOTIATING")!;
    addLeadFollowUp(lead.leadNo, { content: "唯一标记词 zebra", channel: "EMAIL" });
    const page = listLeadFollowUps(lead.leadNo, { size: 50 });

    expect(page.list.every((x) => x.leadNo === lead.leadNo)).toBe(true);
    expect(page.list[0].content).toContain("zebra");
    const times = page.list.map((x) => x.createdAt);
    expect(times).toEqual([...times].sort().reverse());
    expect(listLeadFollowUps(lead.leadNo, { keyword: "zebra" }).total).toBe(1);
  });
});

describe("公共线索池与认领", () => {
  it("池视图只列在池的，在跟视图不含池里的", () => {
    const pool = listLeads({ size: 500, inPool: true }).list;
    expect(pool.length).toBeGreaterThan(0);
    expect(pool.every((l) => l.inPool && !l.owner)).toBe(true);
    expect(listLeads({ size: 500, inPool: false }).list.some((l) => l.inPool)).toBe(false);
  });

  it("认领：成为负责人、出池、重新计时；再认领一次报「不在池中」", () => {
    const l = leads.find((x) => x.inPool)!;
    const before = l.lastFollowAt;
    const r = claimLead(l.leadNo);
    expect(r).toMatchObject({ inPool: false, owner: "admin", ownerType: "STAFF" });
    expect(r.lastFollowAt).not.toBe(before);
    expect(() => claimLead(l.leadNo)).toThrow(/不在公共线索池/);
  });
});

describe("商机保存：查重与 R1", () => {
  it("★ 查重：同场地名或地址 90 天内别人在跟 → 拒绝，并说出是谁在跟", () => {
    const name = `查重场地-${Date.now()}`;
    const busy = saveLead({ venueName: name, address: `${name} Rd`, owner: "BD-Layla" });
    // 大小写 / 首尾空白不算不同
    expect(() => saveLead({ venueName: `  ${name.toUpperCase()} ` }))
      .toThrow(new RegExp(`${busy.leadNo}.*BD-Layla`));
    expect(() => saveLead({ venueName: "换了个名字", address: `${name} Rd` }), "地址相同也算").toThrow(/BD-Layla/);
    // 自己重复录不拦（可能是补录）
    expect(() => saveLead({ venueName: name, owner: "BD-Layla" })).not.toThrow();
  });

  it("新建不采信服务端字段：带 contractNo / inPool 也落成空", () => {
    const l = saveLead({ venueName: `查重外-${Date.now()}`, contractNo: "CT-FAKE", inPool: true } as never);
    expect(l).toMatchObject({ contractNo: null, inPool: false, stage: "NEW", owner: "admin" });
  });

  it("编辑改阶段必须是合法迁移；条款平铺进来、收成 terms 出去", () => {
    const l = saveLead({ venueName: `条款-${Date.now()}`, shareMode: "SHARE", shareRate: 0.18, termMonths: 24, exclusiveFlag: true });
    expect(l.terms).toMatchObject({ shareMode: "SHARE", shareRate: 0.18, termMonths: 24, exclusive: true });
    expect(() => saveLead({ leadNo: l.leadNo, stage: "SIGNED" })).toThrow(/不能从 NEW 直接变为 SIGNED/);
    expect(() => saveLead({ leadNo: l.leadNo, stage: "LOST" }), "丢单要原因").toThrow(/原因/);
    expect(saveLead({ leadNo: l.leadNo, contact: "新联系人" }).stage).toBe("NEW");
  });
});

describe("签约转化", () => {
  it("★ 一次生成场地方 + 筹备中站点 + 合同草稿，商机回填三个编号并转 SIGNED", () => {
    const l = saveLead({ venueName: `转化-${Date.now()}`, address: "Sheikh Zayed Rd", shareMode: "SHARE", shareRate: 0.22, termMonths: 6 });
    addLeadFollowUp(l.leadNo, { content: "首访", channel: "CALL", toStage: "CONTACTED" });
    addLeadFollowUp(l.leadNo, { content: "谈条款", channel: "VISIT", toStage: "NEGOTIATING" });
    expect(() => convertLead(l.leadNo, { startAt: "2026-10-01" }), "新建站点要营业时间与区域").toThrow(/regionId|营业时间/);
    expect(getLead(l.leadNo).stage, "失败的转化不动商机").toBe("NEGOTIATING");
    const r = convertLead(l.leadNo, { startAt: "2026-10-01", regionId: "DU-DT", openHours: "10:00-22:00" });
    expect(r).toMatchObject({ venueCreated: true, siteCreated: true });
    const ct = contracts.find((c) => c.contractNo === r.contractNo)!;
    expect(ct).toMatchObject({ status: "DRAFT", shareRate: 0.22, startAt: "2026-10-01", endAt: "2027-03-31", siteNo: r.siteNo, venueNo: r.venueNo });
    expect(ct.flow?.sourceLeadNo).toBe(l.leadNo);
    expect(sites.find((s) => s.siteNo === r.siteNo)?.status).toBe("PREPARING");
    expect(venues.some((v) => v.venueNo === r.venueNo)).toBe(true);
    expect(getLead(l.leadNo)).toMatchObject({ stage: "SIGNED", contractNo: r.contractNo, siteNo: r.siteNo, venueNo: r.venueNo });
    expect(() => convertLead(l.leadNo), "重复转化会生出第二份草稿").toThrow(/已签约转化/);
  });

  it("没谈到洽谈中的商机不能转", () => {
    const l = saveLead({ venueName: `未谈-${Date.now()}` });
    expect(() => convertLead(l.leadNo)).toThrow(/不能从 NEW 直接变为 SIGNED/);
  });

  it("关联已有站点时，站点必须属于所选场地方", () => {
    const l = saveLead({ venueName: `错配-${Date.now()}` });
    addLeadFollowUp(l.leadNo, { content: "a", channel: "CALL", toStage: "CONTACTED" });
    addLeadFollowUp(l.leadNo, { content: "b", channel: "CALL", toStage: "NEGOTIATING" });
    const site = sites.find((s) => s.venueNo)!;
    const other = venues.find((v) => v.venueNo !== site.venueNo)!;
    expect(() => convertLead(l.leadNo, { venueNo: other.venueNo, siteNo: site.siteNo })).toThrow(/不属于场地方/);
  });
});
