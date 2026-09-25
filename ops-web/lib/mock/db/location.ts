// 场所域（ADR-013 场地方 → 站点 → 点位 → 合同）：sites / locations / venues / contracts，
// 外加拓展侧的线索 leads、站点效益分析 siteAnalyses、场地入驻审核 venueOnboardings、站点生命周期 siteLifecycles。
import type {
  Site, SitePoint, Venue, Contract, ContractAttachment, ContractAttachmentReq,
  Lead, LeadStage, LeadFollowUp, LeadFollowUpReq,
  VenueOnboarding, LifecycleRow, FunnelStage, SiteStatus, PageQuery,
} from "../../types";
import {
  SITE_COORD_BOUNDS,
  LEAD_STAGES, LEAD_FOLLOW_CHANNELS, ATTACH_EXTS, ATTACH_MAX_SIZE,
} from "../../types";
import { LOCS, VENUE_NAMES, OPERATORS, p, iso, phone } from "./internal";
import { paginate, kwHit, upsert, nextNo, archiveRow, unarchiveRow } from "./helpers";
import { fail, notFound } from "@/lib/biz-error";

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
const LEAD_PHASE_LABEL: Record<string, string> = {
  NEW: "新线索", CONTACTED: "已接触", NEGOTIATING: "洽谈中", SIGNED: "已签约", LOST: "已流失",
};
const SITE_PHASE_LABEL: Record<SiteStatus, string> = {
  PREPARING: "筹备中", ACTIVE: "营业中", PAUSED: "暂停营业", WITHDRAWING: "撤场中", CLOSED: "已关闭",
};

/** 漏斗档位顺序 = 从线索到闭店的真实先后，页面按它排版。 */
const FUNNEL_ORDER: string[] = [
  "NEW", "CONTACTED", "NEGOTIATING", "SIGNED", "LOST",
  "PREPARING", "ACTIVE", "PAUSED", "WITHDRAWING", "CLOSED",
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

export const listLeads = (q: PageQuery = {}) => paginate(leads, q.page, q.size, (x) => kwHit(q.keyword, x.leadNo, x.venueName, x.owner));
export const listVenueOnboardings = (q: PageQuery = {}) => paginate(venueOnboardings, q.page, q.size, (x) => kwHit(q.keyword, x.onboardingNo, x.venueName, x.contact));
export const listSiteLifecycles = (q: PageQuery & { phase?: string } = {}) =>
  paginate(
    lifecycleRows().filter((x) => !q.phase || x.phase === q.phase),
    q.page, q.size,
    (x) => kwHit(q.keyword, x.no, x.name, x.owner ?? ""),
  );

/** 漏斗计数。**零的档位也要返回** —— 缺档会让漏斗看起来「跳过了一步」。 */
export const siteLifecycleFunnel = (): FunnelStage[] => {
  const rows = lifecycleRows();
  return FUNNEL_ORDER.map((phase) => ({
    phase,
    label: LEAD_PHASE_LABEL[phase] ?? SITE_PHASE_LABEL[phase as SiteStatus] ?? phase,
    count: rows.filter((r) => r.phase === phase).length,
  }));
};

/**
 * 商机保存。
 *
 * <b>拓展归因（签下 + 伙伴 + 已指定站点 → 写一行「拓展」责任）不在这里</b>，
 * 而在 `lib/api/mocks/location.ts`：责任行住在 agent 模块（A2-1 为打破
 * device → location → agent → device 的环挪过去的），从这里 import 它会把那个环重新闭合
 * —— 实测 25 个测试文件整体加载失败。API mock 层本来就拿得到整个 db，组合放那里没有这个问题。
 */
export const saveLead = (x: Partial<Lead>) => upsert(leads, x, "leadNo", () => nextNo("LD", leads));
export const saveVenue = (x: Partial<Venue>) => upsert(venues, x, "venueNo", () => nextNo("VEN", venues));
/**
 * 合同保存。附件不经此路径写入（走 addContractAttachment），但这里必须**兜住不被清空**：
 * 页面的编辑抽屉是字段配置化的，提交体里没有 attachments，直接 upsert 会把已有扫描件抹掉。
 */
export const saveContract = (x: Partial<Contract>) => {
  const cur = contracts.find((c) => c.contractNo === x.contractNo);
  return upsert(contracts, { ...x, attachments: x.attachments ?? cur?.attachments ?? [] }, "contractNo", () => nextNo("CT", contracts));
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
// 合同附件（假上传：只存文件名 + 大小，拍板点 #3）
// POST /api/ops/contracts/{contractNo}/attachments —— ⚠️ 后端尚无此端点
// ————————————————————————————————————————————————————————————————

/** 附件违规（合同不存在 / 文件名或大小不合规 / 同名重复 / 删不存在的附件）。 */
export class ContractAttachmentError extends Error {
  constructor(msg: string) { super(msg); this.name = "ContractAttachmentError"; }
}

const findContract = (contractNo: string): Contract => {
  const c = contracts.find((x) => x.contractNo === contractNo);
  if (!c) throw new ContractAttachmentError(`合同不存在：${contractNo}`);
  return c;
};

/**
 * 挂一份扫描件。**不传字节流**：入参只有文件名与大小，浏览器侧的 File 对象不被读取，
 * 所以这里也无从校验内容真伪——mock 阶段的口径就是「登记一条附件记录」。
 * 返回整份合同而不是附件本身：页面一次响应就能刷新抽屉里的附件列表，不用二次拉取。
 */
export function addContractAttachment(contractNo: string, req: ContractAttachmentReq): Contract {
  const c = findContract(contractNo);
  const name = req?.fileName?.trim();
  if (!name) throw new ContractAttachmentError("文件名必填");
  const ext = name.includes(".") ? name.slice(name.lastIndexOf(".") + 1).toLowerCase() : "";
  if (!(ATTACH_EXTS as readonly string[]).includes(ext)) {
    throw new ContractAttachmentError(`不支持的文件格式「${ext || "无扩展名"}」，仅接受 ${ATTACH_EXTS.join(" / ")}`);
  }
  if (!Number.isFinite(req.size) || req.size <= 0) throw new ContractAttachmentError("文件大小非法：空文件不接受");
  if (req.size > ATTACH_MAX_SIZE) throw new ContractAttachmentError(`文件超过 ${ATTACH_MAX_SIZE / 1024 / 1024}MB 上限，请压缩后再上传`);
  // 同名拒绝：合同附件靠文件名辨识（没有内容哈希可比），允许同名会让「哪份是最新的」无从判断
  if (c.attachments.some((a) => a.fileName === name)) throw new ContractAttachmentError(`同名附件已存在：${name}，请先移除旧件或改名`);

  const seq = contracts.reduce((m, x) => Math.max(m, ...x.attachments.map((a) => Number(a.attachNo.slice(3)) || 0)), 899);
  const row: ContractAttachment = {
    attachNo: `ATT${seq + 1}`, fileName: name, size: req.size,
    uploadedBy: req.uploadedBy?.trim() || "admin", uploadedAt: new Date().toISOString(),
  };
  c.attachments = [row, ...c.attachments]; // 新件置顶，与各处流水一致
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
 * 记一条跟进。与阶段推进**同一个动作**：BD 现实里就是「打完电话顺手把阶段拨过去」，
 * 拆成两步会出现「阶段变了但没人知道为什么」——这正是本次要补的窟窿。
 * 阶段不传即只留痕；传了且与当前不同才动 `lead.stage`，同时把 `lead.updatedAt` 对齐本条时间
 * （列表的「更新时间」= 最后一次跟进时间，两处不能各算一套）。
 */
export function addLeadFollowUp(leadNo: string, req: LeadFollowUpReq): LeadFollowUp {
  const lead = leads.find((x) => x.leadNo === leadNo);
  if (!lead) throw new LeadFollowUpError(`线索不存在：${leadNo}`);
  const content = req?.content?.trim();
  if (!content) throw new LeadFollowUpError("跟进内容必填：时间线上一条没有内容的记录等于没记");
  if (!req.channel || !LEAD_FOLLOW_CHANNELS.includes(req.channel)) throw new LeadFollowUpError(`跟进方式非法: ${req.channel}`);
  if (req.stage && !LEAD_STAGES.includes(req.stage)) throw new LeadFollowUpError(`线索阶段非法: ${req.stage}`);

  const from = lead.stage;
  const to = req.stage && req.stage !== from ? req.stage : from;
  const row: LeadFollowUp = {
    followNo: nextNo("LF", leadFollowUps, 5000, "followNo"),
    leadNo, channel: req.channel,
    // 阶段没动就不制造「A → A」的假迁移：fromStage 记 null，时间线只展示阶段徽标
    fromStage: to === from ? null : from,
    toStage: to,
    owner: req.owner?.trim() || lead.owner,
    content, nextAt: req.nextAt?.trim() || null,
    createdAt: new Date().toISOString(),
  };
  leadFollowUps.unshift(row);
  lead.stage = to;
  lead.updatedAt = row.createdAt;
  return row;
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
