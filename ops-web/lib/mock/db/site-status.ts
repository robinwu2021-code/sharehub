import type {
  Site, SiteStatus, SiteSummary, SiteStatusLogItem, Checklist, ChecklistItem, SiteOps,
} from "../../types";
import { SITE_TRANSITIONS } from "../../types";
import { fail } from "../../biz-error";
import { sites, contracts, locations } from "./location";

/**
 * 站点状态机与门禁的 mock。
 *
 * <h3>与后端同规则，非法迁移抛错</h3>
 * mock 放行而后端拒绝 = 离线调一路顺、切后端当场 500（本仓库踩过四次）。
 *
 * <h3>门禁是向导，不是拦路虎</h3>
 * 每条未通过都要带 `fixHref` 指向能解决它的页面。只说「合同未生效」而不给去处，
 * 运营得自己猜去哪儿办 —— 那样的门禁只会让人想绕过它。
 */

const logs = new Map<string, SiteStatusLogItem[]>();

const now = () => new Date().toISOString();
const today = () => now().slice(0, 10);

function find(siteNo: string): Site {
  const s = sites.find((x) => x.siteNo === siteNo);
  if (!s) fail(`站点不存在：${siteNo}`, `Site not found: ${siteNo}`, `الموقع غير موجود: ${siteNo}`);
  return s;
}

function opsOf(s: Site): SiteOps {
  if (!s.ops) {
    s.ops = {
      opsEmployeeNo: s.opsEmployeeNo ?? null, operateAgentNo: null, firstLiveAt: null,
      pauseReason: null, pauseUntil: null, withdrawReason: null, withdrawPlannedAt: null,
      closedAt: null, activeContractNo: null,
    };
  }
  return s.ops;
}

/** 状态留痕。导出给 operation.ts 的 pause/resume 用——留痕只此一份，不抄第二遍。 */
export function siteStatusLog(s: Site, event: string, from: SiteStatus | null, to: SiteStatus | null, reason?: string) {
  const list = logs.get(s.siteNo) ?? [];
  list.unshift({ event, fromStatus: from, toStatus: to, operator: "admin", reason: reason ?? null, at: now() });
  logs.set(s.siteNo, list);
}

/** 状态机闸。说清当前在哪、允许从哪来 —— 只说「不允许」会让人以为是权限问题。 */
function must(s: Site, action: keyof typeof SITE_TRANSITIONS): SiteStatus {
  const t = SITE_TRANSITIONS[action];
  if (!t.from.includes(s.status)) {
    fail(
      `站点 ${s.siteNo} 当前是「${s.status}」，不能执行「${action}」（允许：${t.from.join("/")}）`,
      `Site ${s.siteNo} is ${s.status}, cannot ${action}`,
      `الموقع ${s.siteNo} في حالة ${s.status}`,
    );
  }
  return t.to;
}

export const getSite = (no: string): Site => {
  const s = find(no);
  opsOf(s);
  return s;
};

export const listSiteStatusLogs = (no: string): SiteStatusLogItem[] => {
  find(no);
  return logs.get(no) ?? [];
};

/**
 * 开业清单：筹备中还差什么。
 *
 * <p>四条都取自真实数据，不是写死的 true —— 写死的清单永远全绿，
 * 于是它看起来在工作而实际什么都没查。
 */
export function siteOpeningChecklist(no: string): Checklist {
  const s = find(no);
  const o = opsOf(s);
  const hasContract = contracts.some((c) => c.siteNo === no && c.status === "ACTIVE");
  const hasPoint = locations.some((l) => l.siteNo === no && !l.archivedAt);
  const items: ChecklistItem[] = [
    {
      key: "contract", label: "有生效中的进场合同", passed: hasContract,
      detail: hasContract ? null : "没有生效中的合同 —— 无合同营业是合规风险",
      fixHref: hasContract ? null : "/venues?tab=contracts",
    },
    {
      key: "openHours", label: "已填营业时间", passed: !!s.openHours,
      detail: s.openHours ? null : "缺营业时间 —— 借还高峰与巡检排班都按它算",
      fixHref: s.openHours ? null : `/operation/sites?keyword=${no}`,
    },
    {
      key: "owner", label: "已指定运维责任人", passed: !!(o.opsEmployeeNo || o.operateAgentNo),
      detail: (o.opsEmployeeNo || o.operateAgentNo) ? null : "没有责任人 —— 出故障时没人认领",
      fixHref: (o.opsEmployeeNo || o.operateAgentNo) ? null : `/operation/sites?keyword=${no}`,
    },
    {
      key: "point", label: "至少有一个点位", passed: hasPoint,
      detail: hasPoint ? null : "还没有点位 —— 设备没地方放",
      fixHref: hasPoint ? null : "/locations?tab=points",
    },
  ];
  return { allPassed: items.every((i) => i.passed), items };
}

/** 关闭门禁：撤场中还有什么没了结。 */
export function siteCloseGate(no: string): Checklist {
  const s = find(no);
  const cabinets = s.cabinetCount ?? 0;
  const activeContract = contracts.find((c) => c.siteNo === no && c.status === "ACTIVE");
  const items: ChecklistItem[] = [
    {
      key: "cabinets", label: "设备已全部撤出", passed: cabinets === 0,
      detail: cabinets === 0 ? null : `还有 ${cabinets} 台设备在站 —— 关站后它们会变成找不到归属的资产`,
      fixHref: cabinets === 0 ? null : "/devices?tab=inventory",
    },
    {
      key: "contract", label: "合同已终止或到期", passed: !activeContract,
      detail: activeContract ? `合同 ${activeContract.contractNo} 仍生效 —— 关站但合同还在，分成会继续算` : null,
      fixHref: activeContract ? "/venues?tab=contracts" : null,
    },
  ];
  return { allPassed: items.every((i) => i.passed), items };
}

/*
 * pauseSite / resumeSite **不在这里** —— mock/db/operation.ts 早就有一份，
 * 且已接在 mocks/operation.ts 上。写第二份的话，两处校验迟早不一致，
 * 而调用方只会命中其中一个，另一份的规则**永远不生效却看着在那儿**。
 * 那一份已按本文件的状态机口径加固（见 operation.ts）。
 */

export function withdrawSite(no: string, reason: string, plannedAt?: string): Site {
  const s = find(no);
  const to = must(s, "withdraw");
  if (!reason?.trim()) fail("撤场原因必填", "Withdraw reason required", "سبب الانسحاب مطلوب");
  const from = s.status;
  s.status = to;
  const o = opsOf(s);
  o.withdrawReason = reason;
  o.withdrawPlannedAt = plannedAt ?? null;
  siteStatusLog(s, "WITHDRAW", from, to, reason);
  return s;
}

/**
 * 关闭。**门禁不过就拒** —— 与后端同规则。
 *
 * <p>前端按钮虽然也会按门禁禁用，但那只是提示：
 * mock 这一层放行的话，离线开发时能关掉一个还有设备在站的站点，
 * 而线上会被拒 —— 两边行为不一致比缺功能更难查。
 */
export function closeSite(no: string, note?: string): Site {
  const s = find(no);
  const to = must(s, "close");
  const gate = siteCloseGate(no);
  if (!gate.allPassed) {
    const blocked = gate.items.filter((i) => !i.passed).map((i) => i.label).join("、");
    fail(
      `还不能关闭：${blocked}`,
      `Cannot close: ${blocked}`,
      `لا يمكن الإغلاق: ${blocked}`,
    );
  }
  const from = s.status;
  s.status = to;
  opsOf(s).closedAt = now();
  siteStatusLog(s, "CLOSE", from, to, note);
  return s;
}

export function siteSummary(): SiteSummary {
  const live = sites.filter((s) => !s.archivedAt);
  const by = (st: SiteStatus) => live.filter((s) => s.status === st).length;
  return {
    preparing: by("PREPARING"),
    active: by("ACTIVE"),
    paused: by("PAUSED"),
    withdrawing: by("WITHDRAWING"),
    closed: by("CLOSED"),
    // 后两个是要人动手的事：缺了站点照常营业，所以没人会主动发现
    missingOwner: live.filter((s) => s.status !== "CLOSED" && !(s.opsEmployeeNo || s.ops?.operateAgentNo)).length,
    missingOpenHours: live.filter((s) => s.status !== "CLOSED" && !s.openHours).length,
  };
}

/** 供别的切片复用（开业清单里要判「首台设备上线」）。 */
export const todayStr = today;
