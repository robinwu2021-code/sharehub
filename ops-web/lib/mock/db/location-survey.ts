import type { SiteSurvey, SurveyReq, SignalLevel, SurveyResult } from "../../types";
import { fail, notFound } from "../../biz-error";
import { sites } from "./location";
import { getFile, bindFile } from "./file";

/**
 * 站点现场勘测（C1）的 mock —— 与后端 `SiteServiceImpl.recordSurvey` 同规则。
 *
 * <h3>只增不改</h3>
 * 复勘就再记一条：「上次为什么没过」本身是信息，覆盖掉就没人知道这个点位曾经没信号。
 * 以**最近一次**为准，开业清单的「现场勘测」一项读它。
 *
 * <h3>判通过有硬条件</h3>
 * 信号 NONE 或不能接电时不能判 PASS —— 没信号借不出也还不了，没电源柜子就是摆设。
 * 判 FAIL 必须写原因：下一个去复勘的人要知道该看什么。
 */

const SIGNALS: readonly SignalLevel[] = ["STRONG", "GOOD", "WEAK", "NONE"];
const RESULTS: readonly SurveyResult[] = ["PASS", "FAIL"];

/** siteNo → 勘测记录（新的在前）。 */
const surveys = new Map<string, SiteSurvey[]>();
let seq = 1000;

/*
 * 种子：前两个站点各有勘测史 —— 一个「先不过、复勘通过」，一个「最近一次不过」。
 * 两态都要能在页面上看到：只有通过的样本，「最近一次为准」这条规则就看不出来。
 */
function seed() {
  const [a, b] = sites;
  if (a) {
    surveys.set(a.siteNo, [
      { surveyNo: `SVY${seq++}`, siteNo: a.siteNo, signalLevel: "GOOD", powerOk: true, placementNote: "B1 扶梯口左侧，靠柱",
        fileNos: [], result: "PASS", note: null, surveyedBy: "BD-Layla", surveyedAt: "2026-07-12T10:00:00Z" },
      { surveyNo: `SVY${seq++}`, siteNo: a.siteNo, signalLevel: "WEAK", powerOk: false, placementNote: null,
        fileNos: [], result: "FAIL", note: "原定位置无插座，物业答应下周拉线", surveyedBy: "BD-Layla", surveyedAt: "2026-07-05T10:00:00Z" },
    ]);
  }
  if (b) {
    surveys.set(b.siteNo, [
      { surveyNo: `SVY${seq++}`, siteNo: b.siteNo, signalLevel: "NONE", powerOk: true, placementNote: "地下停车场入口",
        fileNos: [], result: "FAIL", note: "地下二层无 4G 信号，需换到 L1", surveyedBy: "BD-Yusuf", surveyedAt: "2026-07-20T09:30:00Z" },
    ]);
  }
}
seed();

export function listSiteSurveys(siteNo: string): SiteSurvey[] {
  if (!sites.some((s) => s.siteNo === siteNo)) notFound("站点", "Site", siteNo);
  return surveys.get(siteNo) ?? [];
}

/** 最近一次勘测是否通过。null = 还没勘测过（开业清单里与「没通过」分开说）。 */
export function latestSurveyPassed(siteNo: string): boolean | null {
  const last = (surveys.get(siteNo) ?? [])[0];
  return last ? last.result === "PASS" : null;
}

export function recordSiteSurvey(siteNo: string, r: SurveyReq): SiteSurvey {
  const site = sites.find((s) => s.siteNo === siteNo);
  if (!site) notFound("站点", "Site", siteNo);
  if (site.status === "CLOSED") fail("已关闭的站点不能编辑", "A closed site is read-only");
  if (!r) fail("缺少参数：body", "Missing parameter: body");
  if (!SIGNALS.includes(r.signalLevel)) fail(`取值非法：signalLevel=${r.signalLevel}`, `Invalid signalLevel=${r.signalLevel}`);
  if (typeof r.powerOk !== "boolean") fail("缺少参数：powerOk", "Missing parameter: powerOk");
  if (!RESULTS.includes(r.result)) fail(`取值非法：result=${r.result}`, `Invalid result=${r.result}`);
  if (r.result === "PASS" && (r.signalLevel === "NONE" || !r.powerOk)) {
    fail("现场无信号或不能接电，勘测不能判为通过", "No signal or no power: survey cannot pass");
  }
  if (r.result === "FAIL" && !r.note?.trim()) fail("请填写说明", "A note is required");
  const fileNos = [...new Set((r.fileNos ?? []).filter((x) => x && x.trim()))];
  for (const f of fileNos) {
    const ref = getFile(f);
    if (!ref) fail(`文件不存在：${f}`, `File not found: ${f}`);
    if (ref.category !== "SURVEY_PHOTO") fail(`文件 ${f} 不是勘场照片（用途 ${ref.category}）`, `File ${f} is not a survey photo`);
  }
  const row: SiteSurvey = {
    surveyNo: `SVY${seq++}`, siteNo, signalLevel: r.signalLevel, powerOk: r.powerOk,
    placementNote: r.placementNote?.trim() || null, fileNos, result: r.result, note: r.note?.trim() || null,
    surveyedBy: "admin", surveyedAt: new Date().toISOString(),
  };
  for (const f of fileNos) bindFile(f);   // TEMP → BOUND
  surveys.set(siteNo, [row, ...(surveys.get(siteNo) ?? [])]);
  return row;
}
