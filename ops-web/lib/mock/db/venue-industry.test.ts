// 场地管理三件事在 mock 层的口径：编号服务端取 · 行业是词表 · 商机坐标带进站点。
//
// 【为什么 mock 层也要钉】
// 这三件都属于「界面上能填、填了不算数」那一类，而离线开发时前端唯一能验的就是 mock。
// mock 与真后端口径不一致的话，联调一路顺、切后端才发现 —— 而这三件切过去之后
// 的表现分别是：编号被静默丢弃、行业 400、站点在地图上没落点（不报错）。
import { describe, it, expect } from "vitest";
import * as db from "@/lib/mock/db";
// convertLead 没有从 db 的桶文件导出（组合在 api mock 层），直接引模块
import { convertLead } from "@/lib/mock/db/location";
import { VENUE_INDUSTRIES, VENUE_INDUSTRY_LABEL } from "@/lib/types";

describe("场地方编号与行业", () => {
  it("★ 新建场地方由 mock 取号，形状是 VEN + 顺序号（与后端同形）", () => {
    const a = db.saveVenue({ name: "【测试】取号一" });
    const b = db.saveVenue({ name: "【测试】取号二" });
    expect(a.venueNo).toMatch(/^VEN\d+$/);
    expect(b.venueNo).toMatch(/^VEN\d+$/);
    expect(Number(b.venueNo.slice(3))).toBe(Number(a.venueNo.slice(3)) + 1);
  });

  it("★ 行业词表两端同值——少一档就是「界面上选得出来、后端存不进去」", () => {
    // 后端 VenueIndustry 枚举的 13 档。两边对不上时这条会点名差了哪一个。
    const backend = [
      "SHOPPING_MALL", "COMMERCIAL_RE", "AIRPORT", "TRANSPORT_HUB", "F_AND_B",
      "HOTEL", "MEDICAL", "EDUCATION", "OFFICE", "ENTERTAINMENT",
      "RETAIL", "SCENIC", "OTHER",
    ];
    expect([...VENUE_INDUSTRIES].sort()).toEqual([...backend].sort());
    // 每一档都要有中文名，否则列表那一列会是空白
    for (const v of VENUE_INDUSTRIES) expect(VENUE_INDUSTRY_LABEL[v]).toBeTruthy();
  });

  it("种子里的行业全是词表码，不是自由文本", () => {
    /*
     * 归一之前种子写的是「零售 / 航空 / 地产 / 餐饮」，而且配得驴唇不对马嘴
     * （DXB Airports 配到「地产」）。留着中文自由文本的话，下拉一个都选不中，
     * 界面上那一列会全是空白 —— 而空白看着像「没填」，不像「值不对」。
     */
    const bad = db.venues.filter((v) => v.industry && !VENUE_INDUSTRIES.includes(v.industry));
    expect(bad.map((v) => `${v.venueNo}=${v.industry}`), "种子里有词表外的行业").toEqual([]);
    expect(db.venues.some((v) => v.industry), "前提：种子里得有行业值").toBe(true);
  });
});

describe("商机地址坐标", () => {
  it("★ 签约转化时坐标随地址带进新建站点——此前那两个位置是区域中心/后端 null", () => {
    const lead = db.saveLead({
      venueName: "【测试】带坐标的商机",
      address: "Dubai Marina Walk",
      lng: 55.138, lat: 25.077,
    });
    // 逐阶段推进：mock 层强制阶段机，不能从 NEW 直接跳 SIGNED（与后端同口径）
    for (const stage of ["CONTACTED", "NEGOTIATING", "SIGNED"] as const) {
      db.saveLead({ leadNo: lead.leadNo, stage });
    }

    /*
     * 区域刻意选 DU-MAR（中心 25.0805 / 55.1403），**与商机上选的点不同**。
     * 实现若退回区域中心而不是用商机坐标，下面两条断言会红 ——
     * 选同一个点的话这个用例分不出「带过去了」和「恰好一样」。
     */
    const r = convertLead(lead.leadNo, { regionId: "DU-MAR", openHours: "10:00-22:00" });
    const site = db.sites.find((s) => s.siteNo === r.siteNo)!;
    expect(site.address).toBe("Dubai Marina Walk");
    // 这两条是本用例的全部意义
    expect(site.lng).toBe(55.138);
    expect(site.lat).toBe(25.077);
  });

  it("新建商机时坐标真的落下来了——不落的话转化那一步无从谈起", () => {
    /*
     * ⚠️ 这条原本写的是「编辑时坐标不会被抹掉」，**那条断言恒绿**：
     * `updateLead` 用 `Object.assign(cur, {…})`，没列出的键根本不会被覆盖；
     * 真后端那一侧是 MyBatis-Plus 的 NOT_NULL 策略，null 字段不进 UPDATE SET。
     * 两层都是「只改传了的」，所以那个隐患在这条路上不成立 ——
     * 一条不可能失败的用例比没有用例更糟，它占着位置让人以为这里有人看着。
     *
     * 换成能失败的：createLead 里漏掉 lng/lat 的话这条当场红（已负对照验过）。
     */
    const lead = db.saveLead({ venueName: "【测试】新建带坐标", address: "A", lng: 55.1, lat: 25.1 });
    expect(lead.lng).toBe(55.1);
    expect(lead.lat).toBe(25.1);

    // 不选点时是 null 而不是 undefined：出参形状要稳定，前端据它判断「有没有选过点」
    const blank = db.saveLead({ venueName: "【测试】新建不带坐标" });
    expect(blank.lng).toBeNull();
    expect(blank.lat).toBeNull();
  });
});
