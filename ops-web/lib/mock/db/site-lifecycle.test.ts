import { describe, it, expect } from "vitest";
import { listSiteLifecycles, siteLifecycleFunnel } from "./location";
import * as db from "./index";
import { SITE_TRANSITIONS, type SiteStatus } from "../../types";

/**
 * 门店生命周期：**只读漏斗**（2026-09-25 起）。
 *
 * <h3>这个文件为什么被整个重写</h3>
 * 原先它测的是 `changeSiteStage` —— 一套可进可退的六阶段（PROSPECTING/SIGNED/LIVE/
 * ACTIVE/CHURNED/CLOSED），与 `sites.status` 各说各话。裁决把两者合并之后，
 * 「推进阶段」这个动作**不存在了**：推商机走 CRM 跟进，推站点走站点状态机。
 *
 * 所以这里改测两件事：**漏斗是从真实数据派生的**（不是第二套种子），
 * 以及**站点状态机的边与 SSOT 一致**。
 */

describe("门店生命周期漏斗", () => {
  it("★ 行由 leads + sites 派生——不是独立种子（独立种子会与站点真实状态各说各话）", () => {
    const rows = listSiteLifecycles({ size: 500 }).list;
    const leadNos = new Set(db.leads.map((l) => l.leadNo));
    const siteNos = new Set(db.sites.map((s) => s.siteNo));
    for (const r of rows) {
      expect(r.kind === "LEAD" ? leadNos.has(r.no) : siteNos.has(r.no), `${r.kind} ${r.no} 不在源数据里`).toBe(true);
    }
    expect(rows.length).toBeGreaterThan(0);
  });

  it("★ 已签约且已落站点的商机不重复计——它由站点那一行接续", () => {
    const rows = listSiteLifecycles({ size: 500 }).list;
    const dup = db.leads.filter((l) => l.stage === "SIGNED" && l.siteNo)
      .filter((l) => rows.some((r) => r.kind === "LEAD" && r.no === l.leadNo));
    expect(dup.map((l) => l.leadNo), "这些商机既算了商机又算了站点，漏斗总数会虚高").toEqual([]);
  });

  it("站点行的 phase 就是站点状态——不再有第二套阶段词表", () => {
    const rows = listSiteLifecycles({ size: 500 }).list.filter((r) => r.kind === "SITE");
    for (const r of rows) {
      const site = db.sites.find((s) => s.siteNo === r.no)!;
      expect(r.phase).toBe(site.status);
    }
  });

  it("按档位筛", () => {
    const all = listSiteLifecycles({ size: 500 }).list;
    const active = listSiteLifecycles({ size: 500, phase: "ACTIVE" }).list;
    expect(active.every((r) => r.phase === "ACTIVE")).toBe(true);
    expect(active.length).toBe(all.filter((r) => r.phase === "ACTIVE").length);
  });

  it("★ 漏斗把零的档位也返回——缺档会让漏斗看起来「跳过了一步」", () => {
    const f = siteLifecycleFunnel();
    expect(f.length).toBeGreaterThanOrEqual(10);
    expect(f.every((x) => x.label && x.label !== x.phase), "每档都要有中文标签").toBe(true);
    const rows = listSiteLifecycles({ size: 500 }).list;
    expect(f.reduce((n, x) => n + x.count, 0)).toBe(rows.length);
  });
});

describe("站点状态机（SSOT）", () => {
  it("★ 撤场与关闭不可逆——关了要重开只能另建站点", () => {
    const targets = Object.values(SITE_TRANSITIONS).map((t) => t.to);
    // CLOSED 不是任何一条边的 from
    const fromsOfClosed = Object.values(SITE_TRANSITIONS).filter((t) => t.from.includes("CLOSED" as SiteStatus));
    expect(fromsOfClosed, "CLOSED 是终态，不该有出边——同一站点号跨两段经营期会让报表对不上").toEqual([]);
    expect(targets).toContain("CLOSED");
  });

  it("PREPARING 只能由 goLive 离开，而 goLive 是系统边（首台设备上线触发）", () => {
    const leaving = Object.entries(SITE_TRANSITIONS).filter(([, t]) => t.from.includes("PREPARING"));
    expect(leaving.map(([k]) => k)).toEqual(["goLive"]);
  });

  it("暂停可恢复——这条是可逆的，别把它和撤场混为一谈", () => {
    expect(SITE_TRANSITIONS.pause.to).toBe("PAUSED");
    expect(SITE_TRANSITIONS.resume.from).toContain("PAUSED");
    expect(SITE_TRANSITIONS.resume.to).toBe("ACTIVE");
  });
});
