// BD 线索跟进记录（CRM 时间线）的守卫与自洽性测试。
//
// CRM 页从前只有「增改档案」：阶段能被改，但**没人知道为什么变的**。补上跟进流水后，
// 三件事必须由 db 层兜住：① 内容/方式/阶段的取值校验；② 跟进与阶段推进是同一个动作
// （改了阶段必留下解释它的那条记录）；③ 列表的「最后跟进」时间与流水首条同源。
// 另有一组种子自洽断言：种子若与线索的 stage/owner 对不上，时间线一眼就是假数据。
import { describe, expect, it } from "vitest";
import { leads, leadFollowUps, listLeadFollowUps, addLeadFollowUp, LeadFollowUpError } from "./location";
import { LEAD_STAGES, type LeadFollowChannel, type LeadStage } from "../../types";

const leadOf = (leadNo: string) => leads.find((x) => x.leadNo === leadNo)!;
const firstWithStage = (stage: LeadStage) => leads.find((x) => x.stage === stage)!;

describe("种子自洽：跟进记录与线索本身对得上", () => {
  it("每条跟进都挂在真实线索上，负责人与线索负责人一致", () => {
    for (const f of leadFollowUps) {
      const lead = leads.find((x) => x.leadNo === f.leadNo);
      expect(lead, `孤儿跟进记录 ${f.followNo}`).toBeTruthy();
      expect(f.owner, `跟进 ${f.followNo} 的负责人与线索不符`).toBe(lead!.owner);
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
    expect(() => addLeadFollowUp(lead.leadNo, { content: "x", channel: "CALL", stage: "WON" as LeadStage })).toThrow(/阶段非法/);
  });
});

describe("跟进与阶段推进是同一个动作", () => {
  it("传 stage：线索阶段跟着变，记录里留下 from → to", () => {
    const lead = leads.find((x) => x.stage === "CONTACTED")!;
    const from = lead.stage;
    const r = addLeadFollowUp(lead.leadNo, { content: "看完点位，进入谈判", channel: "VISIT", stage: "NEGOTIATING", owner: "BD-Layla" });

    expect(r.fromStage).toBe(from);
    expect(r.toStage).toBe("NEGOTIATING");
    expect(leadOf(lead.leadNo).stage).toBe("NEGOTIATING");
    expect(leadOf(lead.leadNo).updatedAt).toBe(r.createdAt); // 更新时间对齐本条
    expect(leadFollowUps[0].followNo).toBe(r.followNo); // 新记录置顶
  });

  it("不传 stage：只留痕不动阶段，且不造「A → A」的假迁移", () => {
    const lead = firstWithStage("NEW");
    const r = addLeadFollowUp(lead.leadNo, { content: "对方未接，改天再拨", channel: "CALL" });
    expect(r.fromStage).toBeNull();
    expect(r.toStage).toBe("NEW");
    expect(leadOf(lead.leadNo).stage).toBe("NEW");
  });

  it("stage 与当前相同视为不改阶段（不产生空转迁移）", () => {
    const lead = leads.find((x) => x.stage === "SIGNED")!;
    const r = addLeadFollowUp(lead.leadNo, { content: "回访确认进场时间", channel: "WHATSAPP", stage: lead.stage });
    expect(r.fromStage).toBeNull();
    expect(leadOf(lead.leadNo).stage).toBe("SIGNED");
  });

  it("阶段可退可跳：LOST 的线索能重新拉回洽谈（BD 现实里半年后再签是常事）", () => {
    const lead = leads.find((x) => x.stage === "LOST")!;
    expect(addLeadFollowUp(lead.leadNo, { content: "对方独家到期，重启谈判", channel: "CALL", stage: "NEGOTIATING" }).toStage).toBe("NEGOTIATING");
    // 取值域就是全部阶段，前端不额外加严（同门店生命周期的口径）
    expect(LEAD_STAGES).toContain("NEGOTIATING");
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
