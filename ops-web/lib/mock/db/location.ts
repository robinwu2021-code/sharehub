// 场所域（ADR-013 场地方 → 站点 → 点位 → 合同）：sites / locations / venues / contracts，
// 外加拓展侧的线索 leads、站点效益分析 siteAnalyses、场地入驻审核 venueOnboardings、站点生命周期 siteLifecycles。
import type {
  Site, SitePoint, Venue, Contract, ContractAttachment, ContractAttachmentReq,
  Lead, LeadStage, LeadFollowUp, LeadFollowUpReq, LeadSaveReq, LeadQ, LeadConvertReq, LeadConversion,
  VenueOnboarding, LifecycleRow, FunnelStage, PageQuery,
} from "../../types";
import {
  SITE_COORD_BOUNDS,
  LEAD_STAGES, LEAD_FOLLOW_CHANNELS, ATTACH_EXTS, ATTACH_MAX_SIZE, leadStageMoveOk,
} from "../../types";
import { LOCS, VENUE_NAMES, OPERATORS, p, iso, phone } from "./internal";
import { paginate, kwHit, upsert, nextNo, archiveRow, unarchiveRow } from "./helpers";
import { fail, notFound } from "@/lib/biz-error";
import { getFile, bindFile } from "./file";

// 台账 M11：站点的区域必须挂 regions 字典里真实存在的三级区域 ID（原先存的是"Dubai North"
// 这类字典里根本没有的名字）。ID 与展示名成对，展示名冗余自字典。
// 区域 + 该区域的真实中心坐标。站点坐标由区域中心加小抖动派生——
// 保证「站点落在它自己声明的区域里」，否则地图上会出现滨海区的站点飘到机场。
const REGIONS: { id: string; name: string; lat: number; lng: number }[] = [
  { id: "DU-MAR", name: "Dubai Marina", lat: 25.0805, lng: 55.1403 },
  { id: "DU-DEI", name: "Deira", lat: 25.2697, lng: 55.3095 },
  { id: "DU-DT", name: "Downtown Dubai", lat: 25.1972, lng: 55.2744 },
  { id: "DU-DXB", name: "DXB 机场", lat: 25.2532, lng: 55.3657 },
  { id: "AZ-YAS", name: "Yas Island", lat: 24.4991, lng: 54.6070 },
];
/** 由区域中心 + 确定性抖动派生站点坐标（同一 i 恒等，避免每次渲染点位乱跳）。 */
const jitter = (base: number, i: number, seed: number) => Number((base + (((i * seed) % 17) - 8) * 0.0035).toFixed(6));
export const sites: Site[] = Array.from({ length: 12 }, (_, i) => ({
  siteNo: `ST${300 + i}`, name: p(LOCS, i),
  // venueNo 与 venueName 必须同源：venues 是 VENUE_NAMES.map 出来的（VEN300 起），
  // 所以这里用同一个下标算编号。此前 mock 只有名字没有编号 —— 与真实库当初的毛病一样，
  // 「合同选站点」这类按编号过滤的联动在 mock 下会一条都筛不出来。
  venueNo: `VEN${300 + (i % VENUE_NAMES.length)}`, venueName: p(VENUE_NAMES, i),
  // 一站一品牌（B1）：演示数据里两个品牌都用上，页面上能看出「不同站点不同品牌」
  brandNo: i % 4 === 0 ? "BR002" : "BR-DEFAULT",
  agentNo: i % 3 === 0 ? null : `AG${String((i % 9) + 1).padStart(3, "0")}`, regionId: p(REGIONS, i).id, regionName: p(REGIONS, i).name,
  address: `${p(LOCS, i)}, Dubai, UAE`,
  lat: jitter(p(REGIONS, i).lat, i, 7), lng: jitter(p(REGIONS, i).lng, i, 11),
  sceneType: p(["商场", "机场", "餐饮", "地铁", "写字楼"], i),
  pointCount: 1 + (i % 4), cabinetCount: 2 + (i * 3) % 10, status: i % 8 === 0 ? "PAUSED" : "ACTIVE",
  archivedAt: null,
}));
export const locations: SitePoint[] = Array.from({ length: 30 }, (_, i) => {
  const site = sites[i % sites.length];
  return {
    locationNo: `LOC${200 + i}`, name: `${site.name} · ${p(["L1东门", "L2中庭", "B1出口", "主入口", "美食广场"], i)}`,
    siteNo: site.siteNo, siteName: site.name, spotDesc: p(["近扶梯", "收银台旁", "入口右侧", "电梯口"], i),
    cabinetCount: 1 + (i % 3), status: i % 9 === 0 ? "PAUSED" : "ACTIVE", archivedAt: null,
  };
});
export const venues: Venue[] = VENUE_NAMES.map((name, i) => ({
  venueNo: `VEN${300 + i}`, name, contact: `+9714${String(2000000 + i * 311).slice(0, 7)}`,
  industry: p(["零售", "航空", "地产", "餐饮"], i), locationCount: 3 + i * 2, archivedAt: null,
}));
export const contracts: Contract[] = Array.from({ length: 18 }, (_, i) => ({
  contractNo: `CT${400 + i}`,
  // 编号必须给：合同是场地方分成的唯一依据，按**编号**连（名字只是展示冗余）。
  // 缺了它，「编辑合同」的场地方/站点两个必填下拉是空的，表单根本提交不了 ——
  // 而这正是真后端侧同一个缺口的镜像（读 DTO 也一直没带出这两列）。
  venueNo: venues[i % venues.length].venueNo,
  siteNo: sites[i % sites.length].siteNo,
  venueName: p(VENUE_NAMES, i), siteName: p(LOCS, i),
  shareRate: [0.15, 0.2, 0.25, 0.3][i % 4], entryFee: (i % 4) * 500, startAt: iso(i * 30 * 86400_000),
  endAt: iso(-(365 - i * 10) * 86400_000), status: i % 9 === 0 ? "EXPIRED" : "ACTIVE",
  // 附件只给一部分合同：空态（「这份合同还没扫描件」）与已上传态都要能在页面上看到。
  // 上传时间 = 合同生效时间之后一天，避免出现「扫描件早于合同生效」这种自相矛盾的流水。
  attachments: i % 3 === 0
    ? [{
        attachNo: `ATT${900 + i}`, fileName: `CT${400 + i}-进场合同扫描件.pdf`,
        size: 1_200_000 + i * 40_000, uploadedBy: p(OPERATORS, i), uploadedAt: iso(i * 30 * 86400_000 - 86400_000),
      }]
    : [],
}));

export const leads: Lead[] = Array.from({ length: 20 }, (_, i) => ({
  leadNo: `LD${3000 + i}`, venueName: p([...VENUE_NAMES, "Dubai Marina Mall", "The Dubai Fountain", "Global Village"], i),
  contact: phone(i), stage: p([...LEAD_STAGES], i),
  // 每 5 条里有一条是伙伴谈下来的 —— 拓展佣金这条路径要有样本，
  // 否则「归属方类型」在页面上永远只看得到一种取值，等于没实现
  ...(i % 5 === 1
    ? { ownerType: "AGENT" as const, owner: `AG00${1 + (i % 6)}` }
    : { ownerType: "STAFF" as const, owner: p(["BD-Layla", "BD-Yusuf", "BD-Ahmed"], i) }),
  expectSites: 1 + (i * 3) % 12,
  // 下次跟进日与跟进记录同源：只有未到终态的线索才有（签约/流失后再约人是无意义的待办）。
  // 两边算法必须一致，否则列表显示「3 天后跟进」而时间线里根本没有这条计划。
  nextFollowAt: p([...LEAD_STAGES], i) !== "SIGNED" && p([...LEAD_STAGES], i) !== "LOST"
    ? iso(-(3 + (i % 5)) * 86400_000).slice(0, 10)
    : null,
  updatedAt: iso(i * 21600_000),
  // 批次 B（V104）的服务端字段。lastFollowAt 与 updatedAt 同源：种子里没有「改了联系人但没跟进」的行
  address: `${p(LOCS, i)}, Dubai`,
  lastFollowAt: iso(i * 21600_000),
  inPool: false,
  prevOwner: null,
  venueNo: null,
  contractNo: null,
  lostReason: p([...LEAD_STAGES], i) === "LOST" ? "对方已与其他品牌签独家" : null,
  lostAt: p([...LEAD_STAGES], i) === "LOST" ? iso(i * 21600_000) : null,
  // 竞品独家到期日只给一部分 LOST 样本：到期前由定时任务重新激活，页面要能看到这一格有值与无值两态
  competitorName: p([...LEAD_STAGES], i) === "LOST" ? "ChargeX" : null,
  competitorExclusiveUntil: p([...LEAD_STAGES], i) === "LOST" && i % 2 === 0 ? iso(-200 * 86400_000).slice(0, 10) : null,
  reactivatedAt: null,
  terms: p([...LEAD_STAGES], i) === "NEGOTIATING"
    ? { shareMode: "SHARE", shareRate: 0.2, entryFee: 0, guaranteeAmount: null, termMonths: 12, exclusive: false }
    : null,
}));

// 跟进记录种子必须与线索自身对得上，否则时间线一看就是假的。三条自洽规则：
//  ① `owner` 全部取该线索的负责人（不会出现「别人的线索被我跟进」）；
//  ② 阶段链从 NEW 走到线索当前 `stage`（LOST 视为在接触后谈崩，不经过 SIGNED）；
//  ③ 最后一条的 `createdAt` **恰好等于** `lead.updatedAt` —— 列表的「更新时间」就是最后一次跟进时间。
// 阶段停留在 NEW 的线索**故意不给记录**：还没人跟进过，正好覆盖空态。
const LEAD_STAGE_PATH: Record<LeadStage, LeadStage[]> = {
  NEW: [],
  CONTACTED: ["NEW", "CONTACTED"],
  NEGOTIATING: ["NEW", "CONTACTED", "NEGOTIATING"],
  SIGNED: ["NEW", "CONTACTED", "NEGOTIATING", "SIGNED"],
  LOST: ["NEW", "CONTACTED", "LOST"],
};
const FOLLOW_TEXT: Record<LeadStage, string> = {
  NEW: "渠道推荐获取线索，已录入待首访",
  CONTACTED: "电话首访：物业对充电宝合作有意向，约现场看点位",
  NEGOTIATING: "现场看点位，谈分成比例与进场费，对方要求月结",
  SIGNED: "合同条款谈定，已签回扫描件，转交运营排期进场",
  LOST: "对方已与其他品牌签独家，本轮结束，半年后再跟",
};
export const leadFollowUps: LeadFollowUp[] = leads.flatMap((lead, i) => {
  const path = LEAD_STAGE_PATH[lead.stage];
  return path.map((to, k) => ({
    followNo: `LF${5000 + i * 10 + k}`,
    leadNo: lead.leadNo,
    channel: p([...LEAD_FOLLOW_CHANNELS], i + k),
    fromStage: k === 0 ? null : path[k - 1],
    toStage: to,
    owner: lead.owner,
    content: FOLLOW_TEXT[to],
    // 只有未到终态的线索才有「下次跟进」计划：签约/流失后再约人是无意义的待办
    nextAt: k === path.length - 1 && to !== "SIGNED" && to !== "LOST" ? iso(-(3 + (i % 5)) * 86400_000).slice(0, 10) : null,
    createdAt: iso(i * 21600_000 + (path.length - 1 - k) * 3 * 86400_000),
  }));
});
/*
 * 公共线索池样本：两条自己人负责、久未跟进的商机已被回收（负责人清空、记下前任）。
 * 放在跟进种子**之后**改：那两条的历史跟进仍是前任记的，时间线要对得上。
 * 挑 i=10（NEW）与 i=12（NEGOTIATING）而不是各阶段的第一条 —— 测试按「第一条某阶段」取样本来跟进，
 * 池里的商机不能跟进（得先认领），挑中它们会让那些用例莫名失败。
 */
for (const i of [10, 12]) {
  const l = leads[i];
  l.prevOwner = l.owner;
  l.owner = null;
  l.inPool = true;
  l.nextFollowAt = null;
  l.lastFollowAt = iso((40 + i) * 86400_000);
}

// 站点坪效（siteAnalyses / listSiteAnalysis）**已迁到 report.ts**。
// 它是读模型不是站点的领域概念：留在这里就等于让业务域依赖报表域，而 report.ts 本就要
// import 本文件的 `sites` —— 两边互引会在模块初始化期炸（实测 22 个测试文件连带失败）。
// 后端出于同一理由把 SiteAnalysis 从 loc.ext 迁进了报表域（见 ReportDtos 的迁入注释）。

export const venueOnboardings: VenueOnboarding[] = [
  { onboardingNo: "OB0001", venueName: "Al Barsha Mall", contact: "Ahmed +971501110001", industry: "购物中心", requestedAt: "2026-07-10T10:00:00Z", status: "PENDING", reviewAt: null, reviewNote: null },
  { onboardingNo: "OB0002", venueName: "Dragon Mart 2", contact: "Lin +971501110002", industry: "商贸城", requestedAt: "2026-07-08T09:00:00Z", status: "APPROVED", reviewAt: "2026-07-09T14:00:00Z", reviewNote: "资料齐全，已通过" },
  { onboardingNo: "OB0003", venueName: "Dune Hotel", contact: "Sara +971501110003", industry: "酒店", requestedAt: "2026-07-05T11:00:00Z", status: "REJECTED", reviewAt: "2026-07-06T10:00:00Z", reviewNote: "流量不足，建议重新评估" },
  { onboardingNo: "OB0004", venueName: "City Walk Shops", contact: "Omar +971501110004", industry: "零售街区", requestedAt: "2026-07-12T08:00:00Z", status: "PENDING", reviewAt: null, reviewNote: null },
];

// 生命周期挂在**真实存在的站点**上（台账 M5：原先是 SITE001–005 / DIFC Gate 等，
// 既不在 sites 的 ST3xx 号段里，站点名也不在 LOCS 里，点进去查无此站点）。
// siteName 一律由 sites 反查，不再手写。
/**
 * 门店生命周期**不再有自己的表** —— 2026-09-25 裁决把它与站点状态合并，
 * `loc_site_lifecycle` 降为变更日志。所以这里也不再有种子数组：
 * 漏斗由 `leads` + `sites` **派生**，与后端 `SiteServiceImpl.lifecycleRows()` 同一口径。
 *
 * <p>留一份独立种子的代价上次已经付过：它与 `sites.status` 各说各话，
 * 界面上同一个站点在「阶段」里是 LIVE、在「站点管理」里是 ACTIVE，谁也说不清它到底在哪。
 */
/** 漏斗档位顺序 = 从线索到闭店的真实先后（与后端 `SiteServiceImpl.funnel` 同序），页面按它排版。 */
const FUNNEL_ORDER: Pick<FunnelStage, "kind" | "phase">[] = [
  ...(["NEW", "CONTACTED", "NEGOTIATING", "SIGNED", "LOST"] as const).map((phase) => ({ kind: "LEAD" as const, phase })),
  ...(["PREPARING", "ACTIVE", "PAUSED", "WITHDRAWING", "CLOSED"] as const).map((phase) => ({ kind: "SITE" as const, phase })),
];

/**
 * mock 里的「进入当前阶段时刻」。
 *
 * <p>`Lead` 与 `Site` 的前端类型都没有时间戳字段（后端有 `created_at` / 状态日志），
 * 所以这里**按序号派生一个确定性的日期** —— 每次加载都一样，
 * 不用 `Math.random()`：随机值会让「停留 3 天」这种列每次刷新都变，
 * 看着像数据在跳，实际只是 mock 在抖。真后端下这两个字段由服务端给。
 */
const mockPhaseSince = (i: number): string =>
  new Date(Date.now() - (i * 3 + 5) * 86_400_000).toISOString();

const daysSince = (iso: string | null): number | null => {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : Math.max(0, Math.floor((Date.now() - t) / 86_400_000));
};

/** 签约前是商机、签约后是站点，拼成一条漏斗。已签约且已落站点的商机由站点接续，不重复计。 */
function lifecycleRows(): LifecycleRow[] {
  const out: LifecycleRow[] = [];
  leads.forEach((l, i) => {
    if (l.stage === "SIGNED" && l.siteNo) return;   // 已落站点 → 由站点那一行接续
    const since = mockPhaseSince(i);
    out.push({
      kind: "LEAD", no: l.leadNo, name: l.venueName, phase: l.stage,
      phaseSince: since, daysInPhase: daysSince(since), owner: l.owner ?? null,
    });
  });
  sites.forEach((st, i) => {
    if (st.archivedAt) return;
    const since = mockPhaseSince(i + leads.length);
    out.push({
      kind: "SITE", no: st.siteNo, name: st.name, phase: st.status,
      phaseSince: since, daysInPhase: daysSince(since), owner: st.opsEmployeeNo ?? null,
    });
  });
  return out;
}

/** 商机列表：关键词 + 阶段 / 负责人 / 是否在池（与后端 `LocExtController.leads` 同一组筛选）。 */
export const listLeads = (q: LeadQ = {}) => paginate(leads, q.page, q.size, (x) =>
  kwHit(q.keyword, x.leadNo, x.venueName, x.owner)
  && (!q.stage || x.stage === q.stage)
  && (!q.owner || x.owner === q.owner)
  && (q.inPool === undefined || !!x.inPool === q.inPool));
export const listVenueOnboardings = (q: PageQuery = {}) => paginate(venueOnboardings, q.page, q.size, (x) => kwHit(q.keyword, x.onboardingNo, x.venueName, x.contact));
export function getVenueOnboarding(onboardingNo: string): VenueOnboarding {
  const o = venueOnboardings.find((x) => x.onboardingNo === onboardingNo);
  if (!o) notFound("进件", "Onboarding", onboardingNo);
  return o;
}
export const listSiteLifecycles = (q: PageQuery & { phase?: string } = {}) =>
  paginate(
    lifecycleRows().filter((x) => !q.phase || x.phase === q.phase),
    q.page, q.size,
    (x) => kwHit(q.keyword, x.no, x.name, x.owner ?? ""),
  );

/** 漏斗计数。**零的档位也要返回** —— 缺档会让漏斗看起来「跳过了一步」。 */
export const siteLifecycleFunnel = (): FunnelStage[] => {
  const rows = lifecycleRows();
  return FUNNEL_ORDER.map(({ kind, phase }) => {
    const hit = rows.filter((r) => r.kind === kind && r.phase === phase);
    const days = hit.map((r) => r.daysInPhase).filter((d): d is number => d != null);
    return {
      kind, phase, count: hit.length,
      // 空档给 null 而不是 0：「平均停留 0 天」与「没人在这一档」不是一回事
      avgDaysInPhase: days.length ? days.reduce((a, b) => a + b, 0) / days.length : null,
    };
  });
};

/**
 * 商机保存。
 *
 * <b>拓展归因（签下 + 伙伴 + 已指定站点 → 写一行「拓展」责任）不在这里</b>，
 * 而在 `lib/api/mocks/location.ts`：责任行住在 agent 模块（A2-1 为打破
 * device → location → agent → device 的环挪过去的），从这里 import 它会把那个环重新闭合
 * —— 实测 25 个测试文件整体加载失败。API mock 层本来就拿得到整个 db，组合放那里没有这个问题。
 */
export function saveLead(x: LeadSaveReq): Lead {
  const cur = x.leadNo ? leads.find((l) => l.leadNo === x.leadNo) : undefined;
  return cur ? updateLead(cur, x) : createLead(x);
}
export const saveVenue = (x: Partial<Venue>) => upsert(venues, x, "venueNo", () => nextNo("VEN", venues));
/**
 * 合同保存。附件不经此路径写入（走 addContractAttachment），但这里必须**兜住不被清空**：
 * 页面的编辑抽屉是字段配置化的，提交体里没有 attachments，直接 upsert 会把已有扫描件抹掉。
 */
export const saveContract = (x: Partial<Contract>) => {
  const cur = contracts.find((c) => c.contractNo === x.contractNo);
  // 表单里已没有状态（R1）：新建不带状态时落 DRAFT（与后端 create 同），编辑保持原状态。
  // 显式带 status 的只剩测试夹具（造一份已生效合同验牵线费），这里不拦它们。
  return upsert(contracts, {
    ...x, status: x.status ?? cur?.status ?? "DRAFT", attachments: x.attachments ?? cur?.attachments ?? [],
  }, "contractNo", () => nextNo("CT", contracts));
};
/**
 * 进件保存 —— **只改内容，不改状态**（与后端 `VenueOnboardingServiceImpl.save` 同口径）。
 *
 * 状态由「审核」这个动作推动，见 {@link reviewVenueOnboarding}。
 * 此前这里是裸 upsert：表单里把「审核状态」下拉改成「已通过」也能存进去，
 * 而真后端根本不受理状态 —— **mock 绿、线上静默不动**，两边口径就是这么分叉的。
 */
export const saveVenueOnboarding = (x: Partial<VenueOnboarding>) => {
  const cur = x.onboardingNo ? venueOnboardings.find((o) => o.onboardingNo === x.onboardingNo) : undefined;
  if (cur && cur.status !== "PENDING") {
    // 审核结论是对「当时那份内容」做的，事后改内容结论就对不上它审过的东西了
    fail(`进件 ${cur.onboardingNo} 已审核，内容不可再改`,
      `Onboarding ${cur.onboardingNo} is already reviewed and can no longer be edited`);
  }
  const next = { ...x, status: cur?.status ?? ("PENDING" as const) };
  return upsert(venueOnboardings, next, "onboardingNo", () => nextNo("OB", venueOnboardings));
};

/**
 * 进件审核。状态机在这一层强制：**只有 PENDING 能审**，重复审核抛错。
 *
 * 通过时**真的建出场地方并回填 venueNo** —— 后端就是这么做的（`RealVenueCreator`）。
 * 只翻状态不建场地方的话，运营下一步想给它签合同时才会发现查无此人。
 */
export const reviewVenueOnboarding = (onboardingNo: string, approve: boolean, note?: string) => {
  const e = venueOnboardings.find((o) => o.onboardingNo === onboardingNo);
  if (!e) notFound("进件", "Onboarding", onboardingNo);
  if (e!.status !== "PENDING") {
    fail(`进件 ${onboardingNo} 已审核，不可重复审核`, `Onboarding ${onboardingNo} is already reviewed`);
  }
  if (!approve && !(note ?? "").trim()) {
    // 不给原因的驳回，申请人只能反复猜着重提，每次都要运营再看一遍
    fail("驳回必须填写原因", "A rejection reason is required");
  }
  e!.status = approve ? "APPROVED" : "REJECTED";
  e!.reviewAt = new Date().toISOString();
  e!.reviewNote = (note ?? "").trim() || null;
  if (approve) {
    const v = saveVenue({ name: e!.venueName, contact: e!.contact, industry: e!.industry });
    e!.venueNo = v.venueNo;
  }
  return { ...e! };
};

// ————————————————————————————————————————————————————————————————
// 站点坐标校验（saveSite 的写入闸门）
// 窗口取值在 lib/types/location.ts（抽屉提示与本层校验同源）。
// ————————————————————————————————————————————————————————————————

/** 坐标缺失 / 非数 / 落在运营窗口外。 */
export class SiteCoordError extends Error {
  constructor(msg: string) { super(msg); this.name = "SiteCoordError"; }
}

/**
 * 站点坐标必填校验。**比 DDL 严一格**（`loc_site.lng/lat` 允许 NULL）——这是有意的：
 * 前端 `Site.lat/lng` 是非空 number，地图组件直接读，一旦落库为空就变成 NaN 撒点，
 * 表现是「地图少了几个站点」而非报错。宽松留给后端，前端在入口处挡住。
 */
export function assertSiteCoords(lat: unknown, lng: unknown): void {
  const { latMin, latMax, lngMin, lngMax } = SITE_COORD_BOUNDS;
  const num = (v: unknown) => (typeof v === "number" ? v : typeof v === "string" && v.trim() !== "" ? Number(v) : NaN);
  const la = num(lat), ln = num(lng);
  if (!Number.isFinite(la) || !Number.isFinite(ln)) throw new SiteCoordError("站点经纬度必填：地图撒点直接读这两个字段，留空的站点不会出现在地图上");
  if (la < latMin || la > latMax || ln < lngMin || ln > lngMax) {
    throw new SiteCoordError(
      `坐标超出运营范围（纬度 ${latMin}~${latMax} / 经度 ${lngMin}~${lngMax}）：当前 ${la}, ${ln}。经纬度写反是最常见的原因`,
    );
  }
}

// ————————————————————————————————————————————————————————————————
// 合同附件：先经文件服务上传（uploadFile → fileNo），再按 fileNo 挂到合同上
// POST /api/ops/contracts/{contractNo}/attachments { fileNos }
// ————————————————————————————————————————————————————————————————

/** 附件违规（合同不存在 / 文件不存在或用途不对 / 删不存在的附件）。 */
export class ContractAttachmentError extends Error {
  constructor(msg: string) { super(msg); this.name = "ContractAttachmentError"; }
}

const findContract = (contractNo: string): Contract => {
  const c = contracts.find((x) => x.contractNo === contractNo);
  if (!c) throw new ContractAttachmentError(`合同不存在：${contractNo}`);
  return c;
};

/**
 * 把已上传的文件挂到合同上（与后端 `ContractServiceImpl.attachFiles` 同口径）：
 * 文件名与大小取文件服务的记录，不信前端声明；同一文件重复挂只算一次；挂上即 TEMP → BOUND。
 * 签署（signContract）与补传附件共用这一份 —— 签署件就是附件，两处各写一份迟早不一致。
 */
export function attachFiles(c: Contract, fileNos: string[]): void {
  const have = new Set(c.attachments.map((a) => a.fileNo).filter(Boolean));
  const seq = contracts.reduce((m, x) => Math.max(m, ...x.attachments.map((a) => Number(a.attachNo.slice(3)) || 0)), 899);
  let n = seq;
  const rows: ContractAttachment[] = [];
  for (const fileNo of [...new Set(fileNos ?? [])]) {
    if (have.has(fileNo)) continue;
    const f = getFile(fileNo);
    if (!f) throw new ContractAttachmentError(`文件不存在：${fileNo}（先上传再挂到合同上）`);
    if (f.category !== "CONTRACT_SCAN") throw new ContractAttachmentError(`文件 ${fileNo} 不是合同扫描件（用途 ${f.category}）`);
    const ext = f.originalName.includes(".") ? f.originalName.slice(f.originalName.lastIndexOf(".") + 1).toLowerCase() : "";
    if (!(ATTACH_EXTS as readonly string[]).includes(ext)) throw new ContractAttachmentError(`不支持的文件格式「${ext || "无扩展名"}」`);
    if (f.sizeBytes > ATTACH_MAX_SIZE) throw new ContractAttachmentError(`文件超过 ${ATTACH_MAX_SIZE / 1024 / 1024}MB 上限`);
    bindFile(fileNo);
    rows.push({
      attachNo: `ATT${++n}`, fileName: f.originalName, size: f.sizeBytes, uploadedBy: "admin",
      uploadedAt: new Date().toISOString(), fileNo, contentType: f.contentType, previewable: f.previewable,
    });
  }
  c.attachments = [...rows, ...c.attachments]; // 新件置顶，与各处流水一致
}

/**
 * 补传扫描件。已到期 / 已终止的合同不再收（后端 `error.contract.ended_no_attach`）——
 * 结束后补进来的「签署件」说明不了签署时的状态。返回整份合同，抽屉一次刷新。
 */
export function addContractAttachment(contractNo: string, req: ContractAttachmentReq): Contract {
  const c = findContract(contractNo);
  if (c.status === "EXPIRED" || c.status === "TERMINATED") {
    throw new ContractAttachmentError(`合同 ${contractNo} 已结束，不能再补传附件`);
  }
  if (!req?.fileNos?.length) throw new ContractAttachmentError("缺少参数 fileNos：先上传文件");
  attachFiles(c, req.fileNos);
  return c;
}

/** 移除附件（误传的扫描件要能撤）。走 remove 而非 DELETE：与 G1 的「状态迁移」表达保持一致。 */
export function removeContractAttachment(contractNo: string, attachNo: string): Contract {
  const c = findContract(contractNo);
  const next = c.attachments.filter((a) => a.attachNo !== attachNo);
  if (next.length === c.attachments.length) throw new ContractAttachmentError(`附件不存在：${attachNo}`);
  c.attachments = next;
  return c;
}

// ————————————————————————————————————————————————————————————————
// BD 线索跟进记录（CRM 时间线）
// GET/POST /api/ops/leads/{leadNo}/follow-ups —— ⚠️ 后端尚无这两个端点
// ————————————————————————————————————————————————————————————————

/** 跟进记录违规（线索不存在 / 内容为空 / 方式或阶段取值非法）。 */
export class LeadFollowUpError extends Error {
  constructor(msg: string) { super(msg); this.name = "LeadFollowUpError"; }
}

/** 某条线索的跟进流水，新的在前。 */
export const listLeadFollowUps = (leadNo: string, q: PageQuery = {}) =>
  paginate(
    leadFollowUps.filter((x) => x.leadNo === leadNo).sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    q.page, q.size, (x) => kwHit(q.keyword, x.content, x.owner),
  );

/**
 * 记一条跟进（与后端 `LeadFollowServiceImpl.addFollowUp` 同规则）。与阶段推进**同一个动作**：
 * 拆成两步会出现「阶段变了但没人知道为什么」。
 *
 * - 池里的商机不能跟进：先认领，否则「谁在跟」又说不清了；
 * - `toStage` 不传 = 只留痕；传了且不同 = 按 {@link leadStageMoveOk} 推进，非法迁移抛错；
 * - 推到 LOST 时本条内容就是丢单原因；LOST → NEW 记重新激活时刻；
 * - 跟进即重新计时：`lastFollowAt` 置为本条时间（提醒与回收都按它算）。
 */
export function addLeadFollowUp(leadNo: string, req: LeadFollowUpReq): LeadFollowUp {
  const lead = leads.find((x) => x.leadNo === leadNo);
  if (!lead) throw new LeadFollowUpError(`线索不存在：${leadNo}`);
  const content = req?.content?.trim();
  if (!content) throw new LeadFollowUpError("跟进内容必填：时间线上一条没有内容的记录等于没记");
  if (!req.channel || !LEAD_FOLLOW_CHANNELS.includes(req.channel)) throw new LeadFollowUpError(`跟进方式非法: ${req.channel}`);
  if (req.toStage && !LEAD_STAGES.includes(req.toStage)) throw new LeadFollowUpError(`线索阶段非法: ${req.toStage}`);
  if (lead.inPool) {
    fail(`商机 ${leadNo} 在公共线索池中，请先认领再跟进`, `Lead ${leadNo} is in the public pool; claim it first`);
  }
  const from = lead.stage;
  const to = req.toStage || from;
  if (!leadStageMoveOk(from, to)) {
    fail(`商机阶段不能从 ${from} 直接变为 ${to}`, `Lead stage cannot move from ${from} to ${to}`);
  }
  const at = new Date().toISOString();
  const row: LeadFollowUp = {
    followNo: nextNo("LF", leadFollowUps, 5000, "followNo"),
    leadNo, channel: req.channel,
    // 与后端一致：每条都记来源阶段（未推进时 from == to），判「推进了」看两者是否不同
    fromStage: from,
    toStage: to,
    owner: "admin",
    content, nextAt: req.nextAt?.trim() || null,
    createdAt: at,
  };
  leadFollowUps.unshift(row);
  if (to !== from) {
    lead.stage = to;
    if (to === "LOST") { lead.lostReason = content; lead.lostAt = at; }
    if (to === "NEW" && from === "LOST") lead.reactivatedAt = at;
  }
  if (row.nextAt) lead.nextFollowAt = row.nextAt;
  lead.lastFollowAt = at;
  lead.updatedAt = at;
  return row;
}

// ————————————————————————————————————————————————————————————————
// 商机：保存（查重 / 阶段迁移 / 服务端字段锁）· 详情 · 认领 · 签约转化
// 与后端 LeadServiceImpl / LeadOpsServiceImpl 同规则
// ————————————————————————————————————————————————————————————————

/** 查重窗口（后端参数 `lead.dedup.days`，默认 90）。 */
export const LEAD_DEDUP_DAYS = 90;
/** mock 的「当前登录人」。认领、建档缺省负责人都记到这个号上。 */
const ME = "admin";

const norm = (v?: string | null) => (v ?? "").trim().toLowerCase() || null;

/** 请求里的平铺条款 → 出参的 terms 子对象。一个都没给时保留原值。 */
function termsOf(x: LeadSaveReq, cur?: Lead["terms"]): Lead["terms"] {
  const keys = ["shareMode", "shareRate", "entryFee", "guaranteeAmount", "termMonths", "exclusiveFlag"] as const;
  if (!keys.some((k) => x[k] !== undefined)) return cur ?? null;
  const num = (v: unknown) => (v === "" || v == null ? null : Number(v));
  return {
    shareMode: x.shareMode ?? cur?.shareMode ?? null,
    shareRate: x.shareRate !== undefined ? num(x.shareRate) : cur?.shareRate ?? null,
    entryFee: x.entryFee !== undefined ? num(x.entryFee) : cur?.entryFee ?? null,
    guaranteeAmount: x.guaranteeAmount !== undefined ? num(x.guaranteeAmount) : cur?.guaranteeAmount ?? null,
    termMonths: x.termMonths !== undefined ? num(x.termMonths) : cur?.termMonths ?? null,
    exclusive: x.exclusiveFlag ?? cur?.exclusive ?? null,
  };
}

/**
 * 查重：同一场地名或地址，窗口内有**别人**在跟 → 拒绝，并说出是谁在跟。
 * 自己重复录不拦（可能是补录）；已丢单与池里的不算「在跟」。
 */
function rejectDuplicate(x: LeadSaveReq, owner: string | null) {
  const name = norm(x.venueName), addr = norm(x.address);
  if (!name && !addr) return;
  const since = Date.now() - LEAD_DEDUP_DAYS * 86400_000;
  const hit = leads.find((l) => l.stage !== "LOST" && !l.inPool
    && Date.parse(l.lastFollowAt ?? l.updatedAt) >= since
    && ((name && norm(l.venueName) === name) || (addr && norm(l.address) === addr))
    && l.owner && l.owner !== owner);
  if (hit) {
    fail(
      `该场地已有商机 ${hit.leadNo} 在跟进（负责人 ${hit.owner}），请联系负责人或等待其回收到线索池`,
      `Lead ${hit.leadNo} for this venue is already being followed by ${hit.owner}`,
    );
  }
}

function createLead(x: LeadSaveReq): Lead {
  const stage = x.stage || "NEW";
  if (!LEAD_STAGES.includes(stage)) fail(`商机阶段非法：${stage}`, `Invalid lead stage: ${stage}`);
  if (stage === "LOST" && !x.lostReason?.trim()) fail("标记丢单必须填写原因", "A lost reason is required");
  const ownerType = x.ownerType || "STAFF";
  const owner = x.owner?.trim() || (ownerType === "STAFF" ? ME : null);
  rejectDuplicate(x, owner);
  const at = new Date().toISOString();
  const lead: Lead = {
    leadNo: nextNo("LD", leads),
    venueName: x.venueName?.trim() ?? "", contact: x.contact ?? null, stage, owner, ownerType,
    siteNo: x.siteNo || null, expectSites: Number(x.expectSites ?? 0) || 0, nextFollowAt: x.nextFollowAt || null,
    updatedAt: at, address: x.address ?? null, venueNo: null,
    // 服务端字段一律不采信入参：新建请求里带 contractNo / inPool 只可能是伪造
    contractNo: null, inPool: false, prevOwner: null, lastFollowAt: at,
    lostReason: stage === "LOST" ? x.lostReason!.trim() : null, lostAt: stage === "LOST" ? at : null,
    competitorName: x.competitorName ?? null, competitorExclusiveUntil: x.competitorExclusiveUntil || null,
    reactivatedAt: null, terms: termsOf(x),
  };
  leads.unshift(lead);
  return lead;
}

function updateLead(cur: Lead, x: LeadSaveReq): Lead {
  const to = x.stage || cur.stage;
  if (!LEAD_STAGES.includes(to)) fail(`商机阶段非法：${to}`, `Invalid lead stage: ${to}`);
  if (!leadStageMoveOk(cur.stage, to)) {
    fail(`商机阶段不能从 ${cur.stage} 直接变为 ${to}`, `Lead stage cannot move from ${cur.stage} to ${to}`);
  }
  const moved = to !== cur.stage;
  if (moved && to === "LOST" && !x.lostReason?.trim()) fail("标记丢单必须填写原因", "A lost reason is required");
  const at = new Date().toISOString();
  // 属性照改；服务端字段（池 / 转化 / 丢单时刻…）从库里取，入参带了也不采信
  const pick = <K extends keyof LeadSaveReq>(k: K, fallback: unknown) => (x[k] !== undefined ? x[k] : fallback);
  Object.assign(cur, {
    venueName: pick("venueName", cur.venueName),
    contact: pick("contact", cur.contact),
    address: pick("address", cur.address),
    expectSites: Number(pick("expectSites", cur.expectSites)) || 0,
    nextFollowAt: pick("nextFollowAt", cur.nextFollowAt) || null,
    siteNo: pick("siteNo", cur.siteNo) || null,
    competitorName: pick("competitorName", cur.competitorName) || null,
    competitorExclusiveUntil: pick("competitorExclusiveUntil", cur.competitorExclusiveUntil) || null,
    terms: termsOf(x, cur.terms),
    updatedAt: at,
  });
  // 在池里的商机负责人只能经认领改
  if (!cur.inPool) {
    cur.ownerType = x.ownerType ?? cur.ownerType;
    cur.owner = x.owner !== undefined ? x.owner || null : cur.owner;
  }
  if (moved) {
    if (to === "LOST") { cur.lostReason = x.lostReason!.trim(); cur.lostAt = at; }
    if (to === "NEW" && cur.stage === "LOST") cur.reactivatedAt = at;
    cur.stage = to;
    cur.lastFollowAt = at;   // 推进阶段本身就是一次跟进
  }
  return cur;
}

export function getLead(leadNo: string): Lead {
  const l = leads.find((x) => x.leadNo === leadNo);
  if (!l) notFound("商机", "Lead", leadNo);
  return l;
}

/** 从公共线索池认领：只有池里的能认领；认领人成为负责人（员工），重新计时。 */
export function claimLead(leadNo: string): Lead {
  const l = getLead(leadNo);
  if (!l.inPool) {
    fail(`商机 ${leadNo} 不在公共线索池中（可能已被他人认领）`, `Lead ${leadNo} is not in the pool (maybe claimed by someone else)`);
  }
  l.inPool = false;
  l.owner = ME;
  l.ownerType = "STAFF";
  l.lastFollowAt = new Date().toISOString();
  l.updatedAt = l.lastFollowAt;
  return l;
}

const addDays = (d: string, n: number) => new Date(Date.parse(`${d}T00:00:00Z`) + n * 86400_000).toISOString().slice(0, 10);
const addMonths = (d: string, n: number) => {
  const t = new Date(`${d}T00:00:00Z`);
  t.setUTCMonth(t.getUTCMonth() + n);
  return t.toISOString().slice(0, 10);
};

/**
 * 签约转化：场地方 + 站点（筹备中）+ 带谈判条款的合同草稿，一次生成。
 *
 * <p>只有洽谈中（或已签但还没转化）的商机能转；已转化过的拒绝（重复转化会生出第二份草稿）。
 * 条款取请求，没给的取商机上谈下来的，再没有按纯分成、12 个月。
 */
export function convertLead(leadNo: string, r: LeadConvertReq = {}): LeadConversion {
  const lead = getLead(leadNo);
  if (lead.contractNo) {
    fail(`商机 ${leadNo} 已签约转化，合同草稿为 ${lead.contractNo}`, `Lead ${leadNo} already converted to ${lead.contractNo}`);
  }
  if (!leadStageMoveOk(lead.stage, "SIGNED")) {
    fail(`商机阶段不能从 ${lead.stage} 直接变为 SIGNED`, `Lead stage cannot move from ${lead.stage} to SIGNED`);
  }
  // 0) 先校验、后落库（后端整段一个事务；mock 没有事务，只能把会失败的检查全放在最前面）
  //    新建站点与「站点管理 › 新增」同口径（后端 SiteServiceImpl.apply）：区域与营业时间必填 ——
  //    营业时间决定离线告警是否计时，缺了会把闭店断电当成故障
  if (!(r.siteNo || lead.siteNo)) {
    if (!r.regionId?.trim()) fail("缺少参数：regionId", "Missing parameter: regionId");
    if (!r.openHours?.trim()) fail("请填写营业时间（决定离线告警是否计时）", "Open hours are required");
  }
  const months = r.termMonths ?? lead.terms?.termMonths ?? 12;
  if (!(months > 0)) fail(`合同期限非法：${months} 个月`, `Invalid term: ${months} months`);
  // 1) 场地方：给了就关联（须存在），没给按商机的场地名新建
  let venueNo = r.venueNo || lead.venueNo || null;
  const givenSite = r.siteNo || lead.siteNo || null;
  if (givenSite) {
    const st = sites.find((x) => x.siteNo === givenSite);
    if (!st) notFound("站点", "Site", givenSite);
    if (st.venueNo && venueNo && st.venueNo !== venueNo) {
      fail(`站点 ${givenSite} 不属于场地方 ${venueNo}`, `Site ${givenSite} does not belong to venue ${venueNo}`);
    }
  }
  let venueCreated = false;
  if (venueNo) {
    if (!venues.some((v) => v.venueNo === venueNo)) notFound("场地方", "Venue", venueNo);
  } else {
    venueNo = saveVenue({ name: lead.venueName, contact: lead.contact ?? "", industry: "", locationCount: 0 }).venueNo;
    venueCreated = true;
  }
  const venue = venues.find((v) => v.venueNo === venueNo)!;
  // 2) 站点：给了就关联（须属于该场地方），没给建一个筹备中的站点
  let siteNo = r.siteNo || lead.siteNo || null;
  let siteCreated = false;
  if (siteNo) {
    const st = sites.find((x) => x.siteNo === siteNo);
    if (!st) notFound("站点", "Site", siteNo);
    if (st.venueNo && st.venueNo !== venueNo) {
      fail(`站点 ${siteNo} 不属于场地方 ${venueNo}`, `Site ${siteNo} does not belong to venue ${venueNo}`);
    }
  } else {
    const region = REGIONS.find((g) => g.id === r.regionId) ?? REGIONS[2];
    const st: Site = {
      siteNo: `ST${300 + sites.length}`, name: r.siteName?.trim() || lead.venueName, venueNo, venueName: venue.name,
      agentNo: null, regionId: region.id, regionName: region.name, address: r.address || lead.address || "",
      lat: region.lat, lng: region.lng, sceneType: "商场", openHours: r.openHours || undefined,
      pointCount: 0, cabinetCount: 0, status: "PREPARING", archivedAt: null,
    };
    sites.unshift(st);
    siteNo = st.siteNo;
    siteCreated = true;
  }
  const site = sites.find((x) => x.siteNo === siteNo)!;
  // 3) 合同草稿
  const t = lead.terms;
  const start = r.startAt || new Date().toISOString().slice(0, 10);
  const contract: Contract = {
    contractNo: nextNo("CT", contracts), venueNo, siteNo, venueName: venue.name, siteName: site.name,
    shareRate: r.shareRate ?? t?.shareRate ?? 0, entryFee: r.entryFee ?? t?.entryFee ?? 0,
    startAt: start, endAt: addDays(addMonths(start, months), -1), status: "DRAFT", attachments: [],
    terms: {
      shareMode: r.shareMode ?? t?.shareMode ?? "SHARE", shareBase: "NET",
      guaranteeAmount: r.guaranteeAmount ?? t?.guaranteeAmount ?? null, currency: "AED", settlePeriod: "MONTH",
      depositAmount: null, depositTerms: null, exclusive: r.exclusive ?? t?.exclusive ?? false, deviceQuota: null,
      placementNote: null, autoRenew: false, signerName: null, remark: `来自商机 ${leadNo}`,
    },
    flow: {
      signedAt: null, submittedBy: null, submittedAt: null, auditedBy: null, auditedAt: null, auditNote: null,
      activatedAt: null, endedAt: null, endReason: null, prevContractNo: null, sourceLeadNo: leadNo,
      contractKind: "MAIN", parentContractNo: null, auditStage: null, financeAuditedBy: null,
      financeAuditedAt: null, financeAuditNote: null, termination: null,
    },
  };
  contracts.unshift(contract);
  // 4) 商机：SIGNED + 回填三个编号
  const at = new Date().toISOString();
  Object.assign(lead, { stage: "SIGNED", venueNo, siteNo, contractNo: contract.contractNo, lastFollowAt: at, updatedAt: at });
  return { leadNo, venueNo, venueCreated, siteNo, siteCreated, contractNo: contract.contractNo };
}

// ————————————————————————————————————————————————————————————————
// 门店生命周期阶段流转（POST /api/ops/site-lifecycles/{siteNo}/stage）
// 取值域与合法性判定在 lib/types/location.ts（页面按钮与本层校验共用同一份），本层**强制执行**——
// mock 比后端松一格，页面就会学到一个线上不存在的操作；紧一格，又会藏掉线上合法的操作。
// ————————————————————————————————————————————————————————————————

/*
 * 「推进阶段」已删除（2026-09-25）。
 *
 * 原先这里有 changeSiteStage + SiteLifecycleError + siteLifecycleLogs 一整套，
 * 且状态机**故意只排空转**（CHURNED→ACTIVE 也放行）。合并之后它是第二套事实：
 * 改它不影响 `sites.status`，于是漏斗好看而站点真实状态没动。
 * 推商机走 CRM 跟进（saveLead / addLeadFollowUp），推站点走站点状态机
 * （pauseSite / resumeSite / withdrawSite / closeSite）。
 */

// —— G1 软删除：站点 / 点位 / 场地方 ——
export const archiveSite = (no: string) => archiveRow(sites, "siteNo", no);
export const unarchiveSite = (no: string) => unarchiveRow(sites, "siteNo", no);
export const archivePoint = (no: string) => archiveRow(locations, "locationNo", no);
export const unarchivePoint = (no: string) => unarchiveRow(locations, "locationNo", no);
export const archiveVenue = (no: string) => archiveRow(venues, "venueNo", no);
export const unarchiveVenue = (no: string) => unarchiveRow(venues, "venueNo", no);
