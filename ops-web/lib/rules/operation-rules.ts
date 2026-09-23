// 运营管理 · 基础管理与公告的业务规则（清单 OM-B1/B3/B4/N1）。
//
// **表单提交前校验与 mock 写入共用这一份**：规则只写一处，页面和 mock 不会各说各话。
// 后端目前没有这些校验（2026-09-22 核对 AppVersion/Bank/Problem/NoticeServiceImpl），
// 已记入 TDD-运营管理菜单-前端.md 的后端待办；后端补上前，前端这一层是唯一的防线。
//
// 纯函数、无 React、无 mock 依赖，可单测。时间统一由参数注入（now），便于测到点行为。
import type { AppVersion, BankEntry, ProblemEntry, Notice } from "../types";

// ——— 应用版本 ————————————————————————————————————————————————

const SEMVER = /^(\d+)\.(\d+)\.(\d+)$/;

/** 比较 x.y.z 版本号：a>b 返回正数，相等 0，a<b 负数。格式不对返回 NaN。 */
export function compareSemver(a: string, b: string): number {
  const ma = SEMVER.exec(a ?? "");
  const mb = SEMVER.exec(b ?? "");
  if (!ma || !mb) return NaN;
  for (let i = 1; i <= 3; i++) {
    const d = Number(ma[i]) - Number(mb[i]);
    if (d !== 0) return d;
  }
  return 0;
}

/** 已发布且全量（100%）的版本里构建号最高的那条 = 该平台「当前线上版本」。 */
export function currentReleased(rows: AppVersion[], platform: AppVersion["platform"]): AppVersion | undefined {
  return rows
    .filter((v) => v.platform === platform && v.status === "RELEASED" && v.rolloutPercent >= 100)
    .sort((a, b) => b.buildNo - a.buildNo)[0];
}

/** 正在灰度的版本（已发布、0 < 比例 < 100）。同一平台最多一条。 */
export function grayRelease(rows: AppVersion[], platform: AppVersion["platform"]): AppVersion | undefined {
  return rows.find((v) => v.platform === platform && v.status === "RELEASED" && v.rolloutPercent > 0 && v.rolloutPercent < 100);
}

/** 版本状态迁移：草稿 → 已发布 → 已回滚。回滚走专用接口，这里一并登记，保证只有一处定义。 */
const VERSION_NEXT: Record<AppVersion["status"], AppVersion["status"][]> = {
  DRAFT: ["RELEASED"],
  RELEASED: ["ROLLBACK"],
  ROLLBACK: [],
};
export function canTransitVersion(from: AppVersion["status"], to: AppVersion["status"]): boolean {
  return from === to || VERSION_NEXT[from].includes(to);
}

/**
 * 保存应用版本前的校验。返回错误文案列表，空数组表示通过。
 * @param next  要保存的完整记录（新建时 versionId 可为空）
 * @param prev  编辑前的记录；新建时为 undefined
 * @param all   同库全部版本（用于跨记录规则：版本号递增、同平台只一个灰度）
 */
export function validateAppVersion(next: Partial<AppVersion>, prev: AppVersion | undefined, all: AppVersion[]): string[] {
  const e: string[] = [];
  const platform = next.platform;
  if (!platform) e.push("请选择平台");
  if (!SEMVER.test(next.versionNo ?? "")) e.push("版本号格式为 x.y.z，例如 1.5.0");
  const from = prev?.status ?? "DRAFT";
  const to = next.status ?? from;
  if (!canTransitVersion(from, to)) e.push(`状态不能从「${VERSION_LABEL[from]}」改为「${VERSION_LABEL[to]}」`);

  // 已发布的版本只允许调整灰度比例；其余字段改动会让已经下载的用户和记录对不上
  if (prev && prev.status !== "DRAFT") {
    const locked: (keyof AppVersion)[] = ["versionNo", "buildNo", "forceUpdate", "minSupported", "downloadUrl"];
    const changed = locked.filter((k) => k in next && next[k] !== undefined && next[k] !== prev[k]);
    if (changed.length) e.push("已发布的版本只能调整灰度比例，其它内容请新建一个版本");
  }

  const pct = Number(next.rolloutPercent ?? 0);
  if (!(pct >= 0 && pct <= 100)) e.push("灰度比例需在 0～100 之间");
  if (next.forceUpdate) {
    if (!SEMVER.test(next.minSupported ?? "")) e.push("强制更新时必须填写最低支持版本（x.y.z）");
    else if (next.versionNo && compareSemver(next.minSupported!, next.versionNo) > 0) e.push("最低支持版本不能高于本版本");
    if (to === "RELEASED" && pct < 100) e.push("强制更新的版本必须全量发布（灰度比例 100%）");
  }
  if (platform && platform !== "H5" && !(next.downloadUrl ?? "").trim()) e.push("iOS / Android 必须填写下载地址");

  if (platform) {
    const others = all.filter((v) => v.platform === platform && v.versionId !== prev?.versionId);
    // 新建：版本号与构建号都必须高于该平台已有的任何一条
    if (!prev && SEMVER.test(next.versionNo ?? "")) {
      const maxVer = others.reduce<string | undefined>((m, v) => (!m || compareSemver(v.versionNo, m) > 0 ? v.versionNo : m), undefined);
      if (maxVer && compareSemver(next.versionNo!, maxVer) <= 0) e.push(`版本号必须高于该平台已有的最高版本 ${maxVer}`);
      const maxBuild = Math.max(0, ...others.map((v) => v.buildNo));
      if (Number(next.buildNo ?? 0) <= maxBuild) e.push(`构建号必须大于该平台已有的最大构建号 ${maxBuild}`);
    }
    // 同平台同时只能有一个版本在灰度
    if (to === "RELEASED" && pct > 0 && pct < 100) {
      const gray = others.find((v) => v.status === "RELEASED" && v.rolloutPercent > 0 && v.rolloutPercent < 100);
      if (gray) e.push(`该平台已有版本 ${gray.versionNo} 正在灰度（${gray.rolloutPercent}%），请先全量或回滚后再灰度新版本`);
    }
  }
  return e;
}

export const VERSION_LABEL: Record<AppVersion["status"], string> = { DRAFT: "草稿", RELEASED: "已发布", ROLLBACK: "已回滚" };

// ——— 银行 ————————————————————————————————————————————————————

/** 常见 MENA 国家的 IBAN 位数（选国家后预填，可改）。 */
export const IBAN_LENGTH_BY_COUNTRY: Record<string, number> = {
  AE: 23, SA: 24, QA: 29, KW: 30, BH: 22, OM: 23, JO: 30, EG: 29,
};

/** 国家的本币（选国家后预填，可改）。 */
export const CURRENCY_BY_COUNTRY: Record<string, string> = {
  AE: "AED", SA: "SAR", QA: "QAR", KW: "KWD", BH: "BHD", OM: "OMR", JO: "JOD", EG: "EGP",
};

export function validateBank(next: Partial<BankEntry>, prev: BankEntry | undefined, all: BankEntry[]): string[] {
  const e: string[] = [];
  const code = (next.bankCode ?? "").trim();
  if (!/^[A-Z0-9]{2,12}$/.test(code)) e.push("银行代码为 2～12 位大写字母或数字");
  if (!prev && all.some((b) => b.bankCode === code)) e.push(`银行代码 ${code} 已存在`);
  if (prev && prev.bankCode !== code) e.push("银行代码创建后不能修改");
  if (!(next.bankName ?? "").trim()) e.push("请填写中文名称");
  if (!/^[A-Z]{2}$/.test(next.country ?? "")) e.push("国家为 ISO 两位大写代码，例如 AE");
  if (!/^[A-Z]{3}$/.test(next.currency ?? "")) e.push("币种为三位大写代码，例如 AED");
  const iban = Number(next.ibanLength);
  if (!(Number.isInteger(iban) && iban >= 15 && iban <= 34)) e.push("IBAN 长度需为 15～34 的整数");
  return e;
}

// ——— 问题类型 ————————————————————————————————————————————————

/**
 * 同分类内上移/下移：与相邻一条交换排序号。返回需要保存的两条（已改好 sortNo），
 * 已在顶/底时返回空数组。只在同分类内移动，跨分类的顺序没有意义（C 端按分类分组展示）。
 */
export function moveProblem(rows: ProblemEntry[], problemNo: string, dir: "up" | "down"): ProblemEntry[] {
  const me = rows.find((r) => r.problemNo === problemNo);
  if (!me) return [];
  const peers = rows
    .filter((r) => r.category === me.category && !r.archivedAt)
    .sort((a, b) => a.sortNo - b.sortNo || a.problemNo.localeCompare(b.problemNo));
  const i = peers.findIndex((r) => r.problemNo === problemNo);
  const j = dir === "up" ? i - 1 : i + 1;
  if (i < 0 || j < 0 || j >= peers.length) return [];
  const other = peers[j];
  // 排序号相同时交换无效，给前者一个明确的先后
  const [a, b] = me.sortNo === other.sortNo
    ? (dir === "up" ? [other.sortNo, other.sortNo + 1] : [other.sortNo + 1, other.sortNo])
    : [other.sortNo, me.sortNo];
  return [{ ...me, sortNo: a }, { ...other, sortNo: b }];
}

// ——— 公告 ————————————————————————————————————————————————————

/** 同时置顶且生效中（或待生效）的公告上限。 */
export const MAX_PINNED = 3;

/**
 * 公告的**展示状态**：库里只有 草稿/已发布/已下线；待生效与已过期由时间派生，不入库。
 */
export type NoticeView = "DRAFT" | "SCHEDULED" | "LIVE" | "EXPIRED" | "OFFLINE";
export function noticeView(n: Pick<Notice, "status" | "startAt" | "endAt">, now: Date = new Date()): NoticeView {
  if (n.status === "DRAFT") return "DRAFT";
  if (n.status === "OFFLINE") return "OFFLINE";
  const t = now.getTime();
  if (n.startAt && new Date(n.startAt).getTime() > t) return "SCHEDULED";
  if (n.endAt && new Date(n.endAt).getTime() <= t) return "EXPIRED";
  return "LIVE";
}

const NOTICE_NEXT: Record<Notice["status"], Notice["status"][]> = {
  DRAFT: ["PUBLISHED"],
  PUBLISHED: ["OFFLINE"],
  OFFLINE: ["PUBLISHED"],
};
export function canTransitNotice(from: Notice["status"], to: Notice["status"]): boolean {
  return from === to || NOTICE_NEXT[from].includes(to);
}

/** 会占用置顶名额的公告：已发布、置顶、未归档、未过期（生效中或待生效）。 */
function occupiesPin(n: Notice, now: Date): boolean {
  const v = noticeView(n, now);
  return n.status === "PUBLISHED" && n.pinned && !n.archivedAt && (v === "LIVE" || v === "SCHEDULED");
}

export function validateNotice(next: Partial<Notice>, prev: Notice | undefined, all: Notice[], now: Date = new Date()): string[] {
  const e: string[] = [];
  if (!(next.title ?? "").trim()) e.push("请填写中文标题");
  if (!(next.content ?? "").trim()) e.push("请填写中文内容");
  if (!next.startAt) e.push("请填写生效开始时间");
  if (next.startAt && next.endAt && new Date(next.endAt).getTime() <= new Date(next.startAt).getTime()) {
    e.push("生效结束时间必须晚于开始时间");
  }
  const from = prev?.status ?? "DRAFT";
  const to = next.status ?? from;
  if (!canTransitNotice(from, to)) e.push(`状态不能从「${NOTICE_STATUS_LABEL[from]}」改为「${NOTICE_STATUS_LABEL[to]}」`);

  const candidate = { ...(prev ?? {}), ...next, status: to, archivedAt: prev?.archivedAt ?? null } as Notice;
  if (occupiesPin(candidate, now)) {
    const taken = all.filter((n) => n.noticeNo !== prev?.noticeNo && occupiesPin(n, now));
    if (taken.length >= MAX_PINNED) {
      e.push(`同时置顶的公告最多 ${MAX_PINNED} 条，当前已置顶：${taken.map((n) => n.title).join("、")}。请先取消其中一条的置顶`);
    }
  }
  return e;
}

export const NOTICE_STATUS_LABEL: Record<Notice["status"], string> = { DRAFT: "草稿", PUBLISHED: "已发布", OFFLINE: "已下线" };
