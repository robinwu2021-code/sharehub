import { describe, it, expect, beforeEach } from "vitest";
import * as ss from "./site-status";
import { pauseSite, resumeSite } from "./operation";
import { sites, contracts, locations } from "./location";
import { recordSiteSurvey } from "./location-survey";
import type { Site } from "../../types";

/**
 * 站点五态与门禁：**mock 的状态机要和后端一样严**。
 *
 * <p>门禁尤其要真查数据 —— 写死 `passed: true` 的清单永远全绿，
 * 于是它看起来在工作而实际什么都没查，比没有清单更糟。
 */

let no: string;

beforeEach(() => {
  no = `ST-T${Date.now()}${Math.floor(Math.random() * 1000)}`;
  sites.unshift({
    siteNo: no, name: "测试站点", venueName: "测试场地", address: "x", regionId: "R1",
    lng: 55, lat: 25, sceneType: "商场", pointCount: 0, cabinetCount: 0,
    status: "PREPARING", archivedAt: null,
  } as unknown as Site);
});

const cur = () => sites.find((s) => s.siteNo === no)!;

describe("站点状态机", () => {
  it("★ 筹备中不能暂停——一个还没开业的站点显示「暂停营业」，谁也说不清它是什么状态", () => {
    expect(() => pauseSite(no, "装修")).toThrowError(/不能暂停|cannot be suspended/);
  });

  it("营业中才能暂停；暂停后能恢复", () => {
    cur().status = "ACTIVE";
    expect(pauseSite(no, "场地装修").status).toBe("PAUSED");
    expect(resumeSite(no).status).toBe("ACTIVE");
  });

  it("暂停原因必填", () => {
    cur().status = "ACTIVE";
    expect(() => pauseSite(no, "  ")).toThrowError(/原因|reason/i);
  });

  it("★ 已关闭是终态，没有任何出边——关了要重开只能另建站点", () => {
    cur().status = "CLOSED";
    expect(() => pauseSite(no, "x")).toThrow();
    expect(() => resumeSite(no)).toThrow();
    expect(() => ss.withdrawSite(no, "x")).toThrow();
    expect(() => ss.closeSite(no)).toThrow();
  });

  const later = (days: number) => new Date(Date.now() + days * 86400_000).toISOString().slice(0, 10);

  it("撤场：营业中或暂停中都可以，原因必填", () => {
    cur().status = "ACTIVE";
    expect(ss.withdrawSite(no, "场地方收回", later(10)).status).toBe("WITHDRAWING");
    cur().status = "PAUSED";
    expect(ss.withdrawSite(no, "再来一次", later(10)).status).toBe("WITHDRAWING");
    cur().status = "ACTIVE";
    expect(() => ss.withdrawSite(no, "", later(10))).toThrowError(/原因|reason/i);
  });

  it("★ 撤场要提前通知：计划日必填，且至少在 N 天之后（与后端 site.withdraw.lead_days 同口径）", () => {
    cur().status = "ACTIVE";
    expect(() => ss.withdrawSite(no, "场地方收回")).toThrowError(/plannedAt/);
    expect(() => ss.withdrawSite(no, "场地方收回", later(ss.SITE_WITHDRAW_LEAD_DAYS - 1))).toThrowError(/至少在|days ahead/);
    expect(cur().status, "被拒的撤场不能改状态").toBe("ACTIVE");
    expect(ss.withdrawSite(no, "场地方收回", later(ss.SITE_WITHDRAW_LEAD_DAYS)).status).toBe("WITHDRAWING");
  });

  it("每一步都留痕，最新在前", () => {
    cur().status = "ACTIVE";
    pauseSite(no, "装修");
    resumeSite(no);
    const events = ss.listSiteStatusLogs(no).map((l) => l.event);
    expect(events[0]).toBe("RESUME");
    expect(events).toContain("PAUSE");
  });
});

describe("开业清单", () => {
  it("★ 五条都真查数据——空站点应该全不通过（写死 true 的清单永远全绿）", () => {
    const c = ss.siteOpeningChecklist(no);
    expect(c.allPassed).toBe(false);
    expect(c.items.map((i) => i.key).sort()).toEqual(["contract", "openHours", "owner", "point", "survey"]);
    expect(c.items.every((i) => !i.passed)).toBe(true);
  });

  it("★ 每条未通过都要给去处——只说「缺什么」而不给链接，门禁就成了拦路虎", () => {
    for (const item of ss.siteOpeningChecklist(no).items) {
      expect(item.passed ? true : !!item.fixHref, `${item.key} 未通过却没有 fixHref`).toBe(true);
      expect(item.detail, `${item.key} 未通过却没说为什么`).toBeTruthy();
    }
  });

  it("补齐条件后逐条转绿", () => {
    const s = cur();
    s.openHours = "09:00-22:00";
    s.opsEmployeeNo = "E001";
    contracts.unshift({ contractNo: `CT-${no}`, siteNo: no, status: "ACTIVE", attachments: [] } as never);
    locations.unshift({ locationNo: `LOC-${no}`, siteNo: no, archivedAt: null } as never);
    recordSiteSurvey(no, { signalLevel: "GOOD", powerOk: true, result: "PASS" });
    const c = ss.siteOpeningChecklist(no);
    expect(c.allPassed, c.items.filter((i) => !i.passed).map((i) => i.label).join("、")).toBe(true);
  });
});

describe("关闭门禁", () => {
  it("★ 还有设备在站时不给关——关站后它们会变成找不到归属的资产", () => {
    const s = cur();
    s.status = "WITHDRAWING";
    s.cabinetCount = 3;
    const gate = ss.siteCloseGate(no);
    expect(gate.allPassed).toBe(false);
    expect(gate.items.find((i) => i.key === "cabinets")?.detail).toContain("3 台");
    expect(() => ss.closeSite(no)).toThrowError(/还不能关闭|Cannot close/);
  });

  it("★ 合同还生效时不给关——关站但合同还在，分成会继续算", () => {
    const s = cur();
    s.status = "WITHDRAWING";
    s.cabinetCount = 0;
    contracts.unshift({ contractNo: `CT-${no}`, siteNo: no, status: "ACTIVE", attachments: [] } as never);
    expect(ss.siteCloseGate(no).allPassed).toBe(false);
    expect(() => ss.closeSite(no)).toThrowError(/还不能关闭|Cannot close/);
  });

  it("门禁全过才能关", () => {
    const s = cur();
    s.status = "WITHDRAWING";
    s.cabinetCount = 0;
    expect(ss.siteCloseGate(no).allPassed).toBe(true);
    expect(() => ss.closeSite(no, " "), "关站不可逆，说明必填").toThrowError(/说明|note/i);
    expect(ss.closeSite(no, "已结清").status).toBe("CLOSED");
    expect(ss.getSite(no).ops?.closedAt).toBeTruthy();
  });
});

describe("摘要条", () => {
  it("后两个是「要人动手的事」：缺运维责任人 / 缺营业时间", () => {
    const before = ss.siteSummary();
    expect(before.missingOwner).toBeGreaterThan(0);
    const s = cur();
    s.opsEmployeeNo = "E001";
    s.openHours = "09:00-22:00";
    const after = ss.siteSummary();
    expect(after.missingOwner).toBe(before.missingOwner - 1);
    expect(after.missingOpenHours).toBe(before.missingOpenHours - 1);
  });
});
