import { describe, it, expect } from "vitest";
import { listSiteSurveys, recordSiteSurvey, latestSurveyPassed } from "./location-survey";
import { siteOpeningChecklist } from "./site-status";
import { sites } from "./location";
import { uploadFile, getFile } from "./file";
import type { Site } from "../../types";

/**
 * 现场勘测：mock 的规则要与后端 SiteServiceImpl.recordSurvey 一样严 ——
 * 判通过的硬条件、不通过必写说明、以最近一次为准（开业清单读它）。
 */
const mkSite = (status: Site["status"] = "PREPARING"): string => {
  const no = `ST-SVY${Date.now()}${Math.floor(Math.random() * 1000)}`;
  sites.unshift({
    siteNo: no, name: "勘测测试", venueName: "x", address: "x", regionId: "R1", regionName: "R1",
    lng: 55, lat: 25, sceneType: "商场", pointCount: 0, cabinetCount: 0, status, archivedAt: null, agentNo: null,
  } as Site);
  return no;
};

describe("现场勘测", () => {
  it("★ 无信号或不能接电不能判通过 —— 没信号借不出也还不了", () => {
    const no = mkSite();
    expect(() => recordSiteSurvey(no, { signalLevel: "NONE", powerOk: true, result: "PASS" })).toThrow(/不能判为通过/);
    expect(() => recordSiteSurvey(no, { signalLevel: "STRONG", powerOk: false, result: "PASS" })).toThrow(/不能判为通过/);
    expect(listSiteSurveys(no), "被拒的不落库").toHaveLength(0);
  });

  it("不通过必须写说明；取值非法拒绝", () => {
    const no = mkSite();
    expect(() => recordSiteSurvey(no, { signalLevel: "WEAK", powerOk: true, result: "FAIL" })).toThrow(/说明/);
    expect(() => recordSiteSurvey(no, { signalLevel: "LOUD" as never, powerOk: true, result: "PASS" })).toThrow(/signalLevel/);
  });

  it("★ 以最近一次为准：先不过、复勘通过 → 开业清单的勘测项转绿；只增不改", () => {
    const no = mkSite();
    expect(latestSurveyPassed(no), "没勘测过是 null，与「没过」分开").toBeNull();
    expect(siteOpeningChecklist(no).items.find((i) => i.key === "survey")).toMatchObject({ passed: false, detail: "还没有勘测记录" });
    recordSiteSurvey(no, { signalLevel: "WEAK", powerOk: false, result: "FAIL", note: "没插座" });
    expect(siteOpeningChecklist(no).items.find((i) => i.key === "survey")?.detail).toBe("最近一次勘测不通过");
    recordSiteSurvey(no, { signalLevel: "GOOD", powerOk: true, result: "PASS" });
    expect(latestSurveyPassed(no)).toBe(true);
    expect(siteOpeningChecklist(no).items.find((i) => i.key === "survey")?.passed).toBe(true);
    expect(listSiteSurveys(no).map((v) => v.result), "新的在前、历史保留").toEqual(["PASS", "FAIL"]);
  });

  it("照片必须是已上传的勘场照片，挂上即转 BOUND", () => {
    const no = mkSite();
    const photo = uploadFile(new File([new Uint8Array(100)], "spot.jpg"), "SURVEY_PHOTO");
    const scan = uploadFile(new File([new Uint8Array(100)], "scan.pdf"), "CONTRACT_SCAN");
    expect(() => recordSiteSurvey(no, { signalLevel: "GOOD", powerOk: true, result: "PASS", fileNos: [scan.fileNo] })).toThrow(/不是勘场照片/);
    const v = recordSiteSurvey(no, { signalLevel: "GOOD", powerOk: true, result: "PASS", fileNos: [photo.fileNo, photo.fileNo] });
    expect(v.fileNos).toEqual([photo.fileNo]);
    expect(getFile(photo.fileNo)?.status).toBe("BOUND");
  });

  it("已关闭的站点不能再记勘测", () => {
    const no = mkSite("CLOSED");
    expect(() => recordSiteSurvey(no, { signalLevel: "GOOD", powerOk: true, result: "PASS" })).toThrow(/已关闭/);
  });
});
