// 计费定价（S6）：差异化规则的站点引用 + 时段表达式结构化的守卫测试。
//
// 两条缺陷本来长这样：① 差异化规则的点位是自由文本，改名/错字后规则永远命中不到；
// ② 时段是自由文本，「周六-周日」「周末」「Sat-Sun」并存，计价端无从解析。
// 修法的关键都不在页面上（下拉只是便利），而在这一层：
//  ① 落库前 siteNo 必须能反查到真站点，场景/展示名由服务端覆盖，不信调用方传的名字；
//  ② period 仍是一列字符串，但 parse ∘ format 必须恒等——否则「编辑一次时段就被改写」。
import { describe, expect, it } from "vitest";
import { pricingDiffs, pricingSchedules, savePricingDiff, savePricingSchedule } from "./pricing";
import { sites, locations } from "./location";
import { parsePeriod, formatPeriod } from "../../types";

describe("差异化定价 · 三维引用（dimension + matchRef）", () => {
  it("种子数据每条规则都指向真实目标，且冗余列与维度自洽", () => {
    const bad: string[] = [];
    pricingDiffs.forEach((d, i) => {
      if (d.dimension === "SITE") {
        const site = sites.find((s) => s.siteNo === d.matchRef);
        if (!site) { bad.push(`pricingDiffs[${i}].matchRef = "${d.matchRef}" 不存在于 sites.siteNo`); return; }
        if (d.siteNo !== site.siteNo) bad.push(`pricingDiffs[${i}].siteNo ≠ matchRef（SITE 维两者必须相等）`);
        if (d.locationName !== site.name) bad.push(`pricingDiffs[${i}].locationName = "${d.locationName}" ≠ sites[${d.matchRef}].name`);
        if (d.scene !== site.sceneType) bad.push(`pricingDiffs[${i}].scene = "${d.scene}" ≠ sites[${d.matchRef}].sceneType`);
      } else if (d.dimension === "LOCATION") {
        const loc = locations.find((l) => l.locationNo === d.matchRef);
        if (!loc) { bad.push(`pricingDiffs[${i}].matchRef = "${d.matchRef}" 不存在于 locations.locationNo`); return; }
        if (d.siteNo !== loc.siteNo) bad.push(`pricingDiffs[${i}].siteNo = "${d.siteNo}" ≠ 点位所属站点 "${loc.siteNo}"`);
        if (d.locationName !== loc.name) bad.push(`pricingDiffs[${i}].locationName = "${d.locationName}" ≠ locations[${d.matchRef}].name`);
      } else {
        if (!sites.some((s) => s.sceneType === d.matchRef)) bad.push(`pricingDiffs[${i}].matchRef = "${d.matchRef}" 不是任何站点的 sceneType`);
        if (d.scene !== d.matchRef) bad.push(`pricingDiffs[${i}].scene ≠ matchRef（SCENE 维两者必须相等）`);
        if (d.siteNo !== "" || d.locationName !== "") bad.push(`pricingDiffs[${i}] SCENE 维不该落站点/点位冗余（siteNo="${d.siteNo}", locationName="${d.locationName}"）`);
      }
    });
    expect(bad, `\n${bad.join("\n")}\n`).toEqual([]);
    // 三个维度在种子里都要出现——页面上看不见的形态等于没实现
    for (const dim of ["SITE", "LOCATION", "SCENE"] as const) {
      expect(pricingDiffs.some((d) => d.dimension === dim), `种子缺 ${dim} 维规则`).toBe(true);
    }
  });

  it("目标不存在时按维度分别拒绝落库（页面下拉之外的入口也挡住）", () => {
    const base = { freeMinutes: 5, unitPrice: 3, capDaily: 30, priority: 1, currency: "AED" };
    expect(() => savePricingDiff({ dimension: "SITE", matchRef: "ST999", ...base })).toThrow(/站点不存在/);
    expect(() => savePricingDiff({ ...base })).toThrow(/站点不存在/);
    expect(() => savePricingDiff({ dimension: "LOCATION", matchRef: "LC999", ...base })).toThrow(/点位不存在/);
    expect(() => savePricingDiff({ dimension: "SCENE", matchRef: "太空站", ...base })).toThrow(/场景不存在/);
  });

  it("冗余列由 matchRef 按维度反查覆盖——调用方传的名字一律不采信", () => {
    const site = sites[3];
    const saved = savePricingDiff({
      dimension: "SITE", matchRef: site.siteNo, scene: "胡编场景", locationName: "早已改名的点位",
      freeMinutes: 5, unitPrice: 3, capDaily: 30, priority: 2, currency: "AED",
    });
    expect(saved.scene).toBe(site.sceneType);
    expect(saved.locationName).toBe(site.name);
    expect(pricingDiffs.find((d) => d.ruleNo === saved.ruleNo)?.locationName).toBe(site.name);

    const loc = locations[5];
    const savedLoc = savePricingDiff({
      dimension: "LOCATION", matchRef: loc.locationNo, siteNo: "ST999", locationName: "乱填",
      freeMinutes: 5, unitPrice: 3, capDaily: 30, priority: 2, currency: "AED",
    });
    expect(savedLoc.siteNo).toBe(loc.siteNo);
    expect(savedLoc.locationName).toBe(loc.name);

    const scene = sites[0].sceneType;
    const savedScene = savePricingDiff({
      dimension: "SCENE", matchRef: scene, siteNo: "ST001", locationName: "乱填",
      freeMinutes: 5, unitPrice: 3, capDaily: 30, priority: 2, currency: "AED",
    });
    expect(savedScene.siteNo).toBe("");
    expect(savedScene.locationName).toBe("");
    expect(savedScene.scene).toBe(scene);
  });

  it("兼容 S6 旧调用形状：不传 dimension 时按 SITE 落，siteNo 充当 matchRef", () => {
    const site = sites[4];
    const saved = savePricingDiff({ siteNo: site.siteNo, freeMinutes: 5, unitPrice: 3, capDaily: 30, priority: 3, currency: "AED" });
    expect(saved.dimension).toBe("SITE");
    expect(saved.matchRef).toBe(site.siteNo);
    expect(saved.locationName).toBe(site.name);
  });
});

describe("时段表达式 · 结构化 ⇄ 字符串", () => {
  it("种子里的每个 period 都能 parse → format 原样回来（不被编辑一次就改写）", () => {
    for (const s of pricingSchedules) {
      expect(formatPeriod(parsePeriod(s.period)), `period「${s.period}」round-trip 不恒等`).toBe(s.period);
    }
  });

  it.each([
    ["周六-周日", "RANGE", "6,7", "", ""],
    ["周一-周五 09:00-18:00", "RANGE", "1,2,3,4,5", "09:00", "18:00"],
    ["22:00-06:00", "RANGE", "", "22:00", "06:00"], // 跨零点合法，不校验 from < to
    ["周一、周三", "RANGE", "1,3", "", ""],
    ["每天", "RANGE", "", "", ""],
    ["公共假日", "EXPR", "", "", ""], // 日历事件表达不了，原文兜底
    ["斋月全月", "EXPR", "", "", ""],
  ])("parse(%s)", (period, kind, days, from, to) => {
    const spec = parsePeriod(period);
    expect(spec.kind).toBe(kind);
    expect(spec.days).toBe(days);
    expect(spec.from).toBe(from);
    expect(spec.to).toBe(to);
    if (kind === "EXPR") expect(spec.expr).toBe(period);
    expect(formatPeriod(spec)).toBe(period);
  });

  it("星期断号用「、」列举，连号收成区间；全空 = 每天（period 永不为空串）", () => {
    expect(formatPeriod({ kind: "RANGE", days: "2,4,6", from: "", to: "", expr: "" })).toBe("周二、周四、周六");
    expect(formatPeriod({ kind: "RANGE", days: "7,6", from: "", to: "", expr: "" })).toBe("周六-周日"); // 乱序输入照样归一
    expect(formatPeriod({ kind: "RANGE", days: "", from: "", to: "", expr: "" })).toBe("每天");
  });

  it("落库时归一化：手输的多余空格不会存出第二种字面", () => {
    const saved = savePricingSchedule({ name: "测试时段", period: "周六 、 周日  18:00 - 22:00", multiplier: 1.5, active: true });
    expect(saved.period).toBe("周六-周日 18:00-22:00");
  });

  it("时段为空拒绝落库", () => {
    expect(() => savePricingSchedule({ name: "空时段", period: "  ", multiplier: 1.5, active: true })).toThrow(/时段不能为空/);
  });
});
