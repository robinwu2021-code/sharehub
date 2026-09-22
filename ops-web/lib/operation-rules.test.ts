import { describe, it, expect } from "vitest";
import {
  compareSemver, currentReleased, grayRelease, validateAppVersion, canTransitVersion,
  validateBank, moveProblem, noticeView, validateNotice, canTransitNotice, MAX_PINNED,
} from "./operation-rules";
import type { AppVersion, BankEntry, ProblemEntry, Notice } from "./types";

const v = (x: Partial<AppVersion>): AppVersion => ({
  versionId: `${x.platform ?? "IOS"}-${x.versionNo ?? "1.0.0"}`, versionNo: "1.0.0", platform: "IOS", buildNo: 100,
  releaseNote: "说明", releaseNoteEn: "", releaseNoteAr: "", forceUpdate: false, minSupported: "",
  rolloutPercent: 100, downloadUrl: "https://x", status: "RELEASED", releasedAt: "2026-01-01T00:00:00Z", ...x,
});

describe("应用版本", () => {
  const all = [
    v({ versionNo: "1.4.0", buildNo: 140, rolloutPercent: 100 }),
    v({ versionNo: "1.5.0", buildNo: 150, rolloutPercent: 30 }),
    v({ platform: "ANDROID", versionNo: "2.0.0", buildNo: 200 }),
  ];

  it("semver 按数值比较，不按字符串", () => {
    expect(compareSemver("1.10.0", "1.9.9")).toBeGreaterThan(0);
    expect(compareSemver("1.2.3", "1.2.3")).toBe(0);
    expect(compareSemver("bad", "1.0.0")).toBeNaN();
  });

  it("线上版本 = 全量中构建号最高；灰度版本单独识别", () => {
    expect(currentReleased(all, "IOS")?.versionNo).toBe("1.4.0");
    expect(grayRelease(all, "IOS")?.versionNo).toBe("1.5.0");
    expect(grayRelease(all, "ANDROID")).toBeUndefined();
  });

  it("状态只能 草稿→已发布→已回滚", () => {
    expect(canTransitVersion("DRAFT", "RELEASED")).toBe(true);
    expect(canTransitVersion("RELEASED", "ROLLBACK")).toBe(true);
    expect(canTransitVersion("ROLLBACK", "RELEASED")).toBe(false);
    expect(canTransitVersion("RELEASED", "DRAFT")).toBe(false);
  });

  it("新建：版本号与构建号必须高于该平台已有的", () => {
    const low = validateAppVersion({ platform: "IOS", versionNo: "1.5.0", buildNo: 151, status: "DRAFT", rolloutPercent: 0, downloadUrl: "u" }, undefined, all);
    expect(low.join()).toContain("版本号必须高于");
    const lowBuild = validateAppVersion({ platform: "IOS", versionNo: "1.6.0", buildNo: 150, status: "DRAFT", rolloutPercent: 0, downloadUrl: "u" }, undefined, all);
    expect(lowBuild.join()).toContain("构建号必须大于");
    // 其它平台的版本号不参与比较
    expect(validateAppVersion({ platform: "ANDROID", versionNo: "2.1.0", buildNo: 201, status: "DRAFT", rolloutPercent: 0, downloadUrl: "u" }, undefined, all)).toEqual([]);
  });

  it("同平台同时只能有一个灰度", () => {
    const draft = v({ versionNo: "1.6.0", buildNo: 160, status: "DRAFT", rolloutPercent: 0 });
    const errs = validateAppVersion({ ...draft, status: "RELEASED", rolloutPercent: 10 }, draft, [...all, draft]);
    expect(errs.join()).toContain("正在灰度");
    // 全量发布不受限
    expect(validateAppVersion({ ...draft, status: "RELEASED", rolloutPercent: 100 }, draft, [...all, draft])).toEqual([]);
  });

  it("强制更新：必须有最低支持版本，且只能全量", () => {
    const draft = v({ platform: "ANDROID", versionNo: "2.1.0", buildNo: 210, status: "DRAFT", rolloutPercent: 0 });
    expect(validateAppVersion({ ...draft, forceUpdate: true }, draft, all).join()).toContain("最低支持版本");
    expect(validateAppVersion({ ...draft, forceUpdate: true, minSupported: "2.0.0", status: "RELEASED", rolloutPercent: 50 }, draft, all).join())
      .toContain("必须全量发布");
    expect(validateAppVersion({ ...draft, forceUpdate: true, minSupported: "2.2.0" }, draft, all).join()).toContain("不能高于本版本");
  });

  it("已发布的版本只能调灰度", () => {
    const rel = all[1];
    expect(validateAppVersion({ ...rel, rolloutPercent: 60 }, rel, all)).toEqual([]);
    expect(validateAppVersion({ ...rel, downloadUrl: "https://other" }, rel, all).join()).toContain("只能调整灰度比例");
  });

  it("iOS/Android 必须有下载地址，H5 不需要", () => {
    const base = { versionNo: "9.0.0", buildNo: 900, status: "DRAFT" as const, rolloutPercent: 0, downloadUrl: "" };
    expect(validateAppVersion({ ...base, platform: "IOS" }, undefined, []).join()).toContain("下载地址");
    expect(validateAppVersion({ ...base, platform: "H5" }, undefined, [])).toEqual([]);
  });
});

describe("银行", () => {
  const all: BankEntry[] = [{ bankCode: "FAB", bankName: "第一银行", bankNameEn: "FAB", country: "AE", currency: "AED", swiftPrefix: "NBAD", ibanLength: 23, status: "ENABLED", archivedAt: null }];
  it("代码唯一、创建后不可改、格式校验", () => {
    expect(validateBank({ ...all[0] }, undefined, all).join()).toContain("已存在");
    expect(validateBank({ ...all[0], bankCode: "FAB2" }, all[0], all).join()).toContain("不能修改");
    expect(validateBank({ ...all[0], bankCode: "fab" }, undefined, []).join()).toContain("大写");
    expect(validateBank({ ...all[0], ibanLength: 40 }, all[0], all).join()).toContain("IBAN");
    expect(validateBank({ ...all[0] }, all[0], all)).toEqual([]);
  });
});

describe("问题排序", () => {
  const p = (no: string, cat: ProblemEntry["category"], sortNo: number): ProblemEntry => ({
    problemNo: no, category: cat, title: no, titleEn: "", titleAr: "", answer: "", answerEn: "", answerAr: "",
    suggestedAction: "TO_CS", sortNo, status: "ENABLED", archivedAt: null,
  });
  const rows = [p("A", "RENT", 1), p("B", "RENT", 2), p("C", "RENT", 3), p("X", "DEVICE", 1)];
  it("同分类内与相邻一条交换排序号", () => {
    expect(moveProblem(rows, "B", "up").map((r) => [r.problemNo, r.sortNo])).toEqual([["B", 1], ["A", 2]]);
    expect(moveProblem(rows, "B", "down").map((r) => [r.problemNo, r.sortNo])).toEqual([["B", 3], ["C", 2]]);
  });
  it("顶部上移、底部下移、跨分类都无操作", () => {
    expect(moveProblem(rows, "A", "up")).toEqual([]);
    expect(moveProblem(rows, "C", "down")).toEqual([]);
    expect(moveProblem(rows, "X", "up")).toEqual([]);
  });
});

describe("公告", () => {
  const now = new Date("2026-09-22T12:00:00Z");
  const n = (no: string, x: Partial<Notice> = {}): Notice => ({
    noticeNo: no, title: no, titleEn: "", titleAr: "", content: "c", contentEn: "", contentAr: "",
    type: "SYSTEM", pinned: false, startAt: "2026-09-01T00:00:00Z", endAt: "2026-12-31T00:00:00Z",
    status: "PUBLISHED", publishedBy: "ops", createdAt: "2026-09-01T00:00:00Z", archivedAt: null, ...x,
  } as Notice);

  it("派生状态：待生效 / 生效中 / 已过期 不入库", () => {
    expect(noticeView(n("a"), now)).toBe("LIVE");
    expect(noticeView(n("a", { startAt: "2026-10-01T00:00:00Z" }), now)).toBe("SCHEDULED");
    expect(noticeView(n("a", { endAt: "2026-09-01T00:00:00Z", startAt: "2026-08-01T00:00:00Z" }), now)).toBe("EXPIRED");
    expect(noticeView(n("a", { status: "OFFLINE" }), now)).toBe("OFFLINE");
    expect(noticeView(n("a", { endAt: "" }), now)).toBe("LIVE"); // 无结束时间 = 长期
  });

  it("状态迁移：草稿→发布→下线→重新发布；不能回到草稿", () => {
    expect(canTransitNotice("DRAFT", "PUBLISHED")).toBe(true);
    expect(canTransitNotice("OFFLINE", "PUBLISHED")).toBe(true);
    expect(canTransitNotice("PUBLISHED", "DRAFT")).toBe(false);
    expect(canTransitNotice("DRAFT", "OFFLINE")).toBe(false);
  });

  it(`置顶上限 ${MAX_PINNED} 条：过期、下线、归档的不占名额`, () => {
    const all = [n("p1", { pinned: true }), n("p2", { pinned: true }), n("p3", { pinned: true }),
      n("old", { pinned: true, startAt: "2026-08-01T00:00:00Z", endAt: "2026-09-01T00:00:00Z" }),
      n("off", { pinned: true, status: "OFFLINE" }), n("arc", { pinned: true, archivedAt: "2026-09-10T00:00:00Z" })];
    const draft = n("new", { status: "DRAFT" });
    expect(validateNotice({ ...draft, status: "PUBLISHED", pinned: true }, draft, all, now).join()).toContain("最多 3 条");
    expect(validateNotice({ ...draft, status: "PUBLISHED", pinned: false }, draft, all, now)).toEqual([]);
    // 编辑已置顶的其中一条，不把自己算进占用
    expect(validateNotice({ ...all[0], title: "改标题" }, all[0], all, now)).toEqual([]);
  });

  it("结束时间必须晚于开始时间", () => {
    expect(validateNotice(n("a", { endAt: "2026-08-01T00:00:00Z" }), undefined, [], now).join()).toContain("晚于开始");
  });
});
