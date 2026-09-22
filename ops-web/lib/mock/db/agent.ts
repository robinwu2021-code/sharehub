// 代理商域：代理商档案 agents / 区域分配 agentAssignments / 业绩排名 agentPerformances /
// 代理账号 agentAccounts / 佣金规则 agentCommissions。
// agents 是全库的“代理商来源”，分润统计（finance.ts）等域一律引用它。
import type {
  Agent, AgentAssignment, AgentPerformance, AgentAccount, AgentCommission,
  AgentAssignmentRecord, AssignableAsset, AssignAssetsPayload, ReclaimAssetsPayload,
  AssetType, AssignAction, PageQuery,
} from "../../types";
import { p, iso, phone, OPERATORS } from "./internal";
import { paginate, kwHit, upsert, nextNo, archiveRow, unarchiveRow } from "./helpers";
import { cabinets } from "./device";
import { sites } from "./location";

export const agents: Agent[] = Array.from({ length: 9 }, (_, i) => ({
  agentNo: `AG${String(i + 1).padStart(3, "0")}`, name: p(["North Hub", "Marina Partner", "Deira Agent", "Airport Ops", "JBR Franchise"], i),
  contact: `+9715${String(6000000 + i * 271).slice(0, 7)}`, regionScope: p(["Dubai North", "Dubai Marina", "Deira", "DXB", "JBR"], i),
  // cabinetCount 由 refreshAgentAssetCounts() 从 cabinets.agentNo 实时反算（下方立即调用一次），
  // 这里给 0 只是占位——档案页的「设备数」与划拨页必须是同一个数。
  shareRate: [0.3, 0.35, 0.4][i % 3], cabinetCount: 0, status: i % 6 === 0 ? "SUSPENDED" : "ENABLED",
  archivedAt: null,
}));

export const agentAssignments: AgentAssignment[] = agents.map((a) => ({
  agentNo: a.agentNo, agentName: a.name, region: a.regionScope,
  cabinetCount: 0, siteCount: 0,
}));

/**
 * 资产归属汇总的**唯一算法**：设备数/点位数一律从 `cabinets.agentNo` / `sites.agentNo` 数出来。
 * 划拨/回收改的是资产上的 agentNo，汇总随之变化——不存计数，就不会出现「划拨了汇总不动」。
 * 列表接口（listAgents / listAgentAssignments / listAgentPerformance）查询前都要调它。
 */
export function refreshAgentAssetCounts(): void {
  for (const a of agents) a.cabinetCount = cabinets.filter((c) => c.agentNo === a.agentNo).length;
  for (const row of agentAssignments) {
    row.cabinetCount = cabinets.filter((c) => c.agentNo === row.agentNo).length;
    row.siteCount = sites.filter((s) => s.agentNo === row.agentNo).length;
  }
  for (const perf of agentPerformances) {
    perf.cabinetCount = agents.find((a) => a.agentNo === perf.agentNo)?.cabinetCount ?? 0;
  }
}
export const agentPerformances: AgentPerformance[] = agents
  .map((a, i) => ({
    agentNo: a.agentNo, agentName: a.name, gmv: 12000 + (i * 4337) % 90000,
    cabinetCount: a.cabinetCount, onlineRate: Number((0.82 + (i % 9) * 0.02).toFixed(2)),
    rank: 0, currency: "AED",
  }))
  .sort((x, y) => y.gmv - x.gmv)
  .map((a, i) => ({ ...a, rank: i + 1 }));
export const agentAccounts: AgentAccount[] = Array.from({ length: 12 }, (_, i) => {
  const a = p(agents, i);
  return {
    accountNo: `AA${7000 + i}`, agentNo: a.agentNo, agentName: a.name, loginPhone: phone(i, "+9714"),
    status: i % 6 === 0 ? "DISABLED" : "ACTIVE", dataScope: p(["AGENT", "REGION", "LOCATION"] as const, i),
    createdAt: iso(i * 86400_000),
  };
});

// 佣金规则挂在**真实存在的代理商**上（台账 M4：原先挂 AGT001–AGT003，agents 里根本没有这些号，
// 佣金规则点进去查无此代理商）。agentName 一律由 agents 反查，不再手写，杜绝名号对不上。
const AGENT_COMMISSION_SEED: Omit<AgentCommission, "agentName">[] = [
  { ruleNo: "AC0001", agentNo: "AG001", basis: "GMV", rate: 0.12, mode: "CHANNEL_SPLIT", effectiveAt: "2026-01-01", status: "ACTIVE" },
  { ruleNo: "AC0002", agentNo: "AG002", basis: "GMV", rate: 0.10, mode: "LEDGER", effectiveAt: "2026-01-01", status: "ACTIVE" },
  { ruleNo: "AC0003", agentNo: "AG003", basis: "ORDER_COUNT", rate: 0.08, mode: "LEDGER", effectiveAt: "2026-03-01", status: "INACTIVE" },
];
export const agentCommissions: AgentCommission[] = AGENT_COMMISSION_SEED.map((r) => ({
  ...r, agentName: agents.find((a) => a.agentNo === r.agentNo)!.name,
}));

export const listAgentAssignments = (q: PageQuery = {}) => {
  refreshAgentAssetCounts();
  return paginate(agentAssignments, q.page, q.size, (x) => kwHit(q.keyword, x.agentNo, x.agentName, x.region));
};
// listAgentPerformance **已迁到 report.ts**：绩效是读模型，GMV 必须与站点坪效同源
// （代理 GMV = 名下站点营收之和）。留在这里会让 agent 域反向依赖 report 域，而 report
// 已经 import 本文件的 agents —— 互引会在模块初始化期炸。同 SiteAnalysis 的处理。
// `agentPerformances` fixture 仅作默认周期快照保留（引用完整性测试用）。
export const listAgentAccounts = (q: PageQuery = {}) => paginate(agentAccounts, q.page, q.size, (x) => kwHit(q.keyword, x.accountNo, x.agentNo, x.agentName, x.loginPhone));
export const listAgentCommissions = (q: PageQuery = {}) => paginate(agentCommissions, q.page, q.size, (x) => kwHit(q.keyword, x.ruleNo, x.agentNo, x.agentName));

export const saveAgentAccount = (x: Partial<AgentAccount>) => upsert(agentAccounts, x, "accountNo", () => nextNo("AA", agentAccounts));
export const saveAgentCommission = (x: Partial<AgentCommission>) => upsert(agentCommissions, x, "ruleNo", () => nextNo("AC", agentCommissions));

// —— G1 软删除：代理商档案 ——
export const archiveAgent = (no: string) => archiveRow(agents, "agentNo", no);
export const unarchiveAgent = (no: string) => unarchiveRow(agents, "agentNo", no);

// ————————————————————————————————————————————————————————————————
// 设备/点位划拨（S1 · 权限码 agent:scope:assign）
//
// 唯一数据流：改的是**资产上的归属字段**——`cabinets[].agentNo` 与 `sites[].agentNo`，
// 别处（代理商档案的设备数、划拨汇总、代理绩效）全部由它反算。回收 = 置 null（平台直营）。
// 每次划拨/回收都落一条 agentAssignmentRecords 流水，谁在什么时候把哪台柜子给了谁可查。
// ————————————————————————————————————————————————————————————————

/** 划拨类错误统一用它抛：页面侧由全局 MutationCache.onError 弹 notify.error。 */
export class AgentAssignError extends Error {
  constructor(msg: string) { super(msg); this.name = "AgentAssignError"; }
}

const agentNameOf = (agentNo: string) => {
  const a = agents.find((x) => x.agentNo === agentNo);
  if (!a) throw new AgentAssignError(`代理商 ${agentNo} 不存在`);
  return a.name;
};

/** 划拨流水（审计）。种子取「当前确实归属某代理」的前几项资产，保证流水与归属现状不矛盾。 */
export const agentAssignmentRecords: AgentAssignmentRecord[] = [
  ...cabinets.filter((c) => c.agentNo).slice(0, 6).map((c, i) => ({
    assignmentNo: `ASG${5000 + i}`, agentNo: c.agentNo!, agentName: agentNameOf(c.agentNo!),
    assetType: "CABINET" as AssetType, assetNo: c.cabinetNo, action: "ASSIGN" as AssignAction,
    operatorName: p(OPERATORS, i), createdAt: iso((i + 4) * 86400_000),
  })),
  ...sites.filter((s) => s.agentNo).slice(0, 4).map((s, i) => ({
    assignmentNo: `ASG${5100 + i}`, agentNo: s.agentNo!, agentName: agentNameOf(s.agentNo!),
    assetType: "SITE" as AssetType, assetNo: s.siteNo, action: "ASSIGN" as AssignAction,
    operatorName: p(OPERATORS, i + 1), createdAt: iso((i + 2) * 86400_000),
  })),
].sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));

export type AssignmentRecordQuery = PageQuery & { agentNo?: string; assetType?: string; action?: string };
export const listAgentAssignmentRecords = (q: AssignmentRecordQuery = {}) =>
  paginate(agentAssignmentRecords, q.page, q.size, (x) =>
    kwHit(q.keyword, x.assignmentNo, x.agentNo, x.agentName, x.assetNo, x.operatorName) &&
    (!q.agentNo || x.agentNo === q.agentNo) &&
    (!q.assetType || x.assetType === q.assetType) &&
    (!q.action || x.action === q.action));

export type AssignableAssetQuery = PageQuery & {
  assetType?: string; // CABINET / SITE
  /** 只列归属该代理的资产（回收抽屉用）。 */
  agentNo?: string;
  /** 排除已归属该代理的资产（划拨抽屉用：只列未归属或归属其它代理的）。 */
  excludeAgentNo?: string;
};
/** 可划拨资产池：机柜 + 站点，带当前归属（选项上标注，避免误划别人的资产）。已归档资产不参与。 */
export const listAssignableAssets = (q: AssignableAssetQuery = {}) => {
  const nameOrNull = (no: string | null) => (no ? agents.find((a) => a.agentNo === no)?.name ?? null : null);
  const rows: AssignableAsset[] = [
    ...cabinets.filter((c) => !c.archivedAt).map((c) => ({
      assetType: "CABINET" as AssetType, assetNo: c.cabinetNo, name: c.locationName || c.sn,
      currentAgentNo: c.agentNo, currentAgentName: nameOrNull(c.agentNo),
    })),
    ...sites.filter((s) => !s.archivedAt).map((s) => ({
      assetType: "SITE" as AssetType, assetNo: s.siteNo, name: s.name,
      currentAgentNo: s.agentNo, currentAgentName: nameOrNull(s.agentNo),
    })),
  ];
  return paginate(rows, q.page, q.size ?? 500, (x) =>
    kwHit(q.keyword, x.assetNo, x.name, x.currentAgentNo) &&
    (!q.assetType || x.assetType === q.assetType) &&
    (!q.agentNo || x.currentAgentNo === q.agentNo) &&
    (!q.excludeAgentNo || x.currentAgentNo !== q.excludeAgentNo));
};

const uniq = (xs: string[] | undefined) => [...new Set((xs ?? []).map((x) => x.trim()).filter(Boolean))];

function logAssignment(agentNo: string, assetType: AssetType, assetNo: string, action: AssignAction, operatorName?: string) {
  const rec: AgentAssignmentRecord = {
    assignmentNo: nextNo("ASG", agentAssignmentRecords, 5200, "assignmentNo"),
    agentNo, agentName: agentNameOf(agentNo), assetType, assetNo, action,
    operatorName: operatorName || "admin", createdAt: new Date().toISOString(),
  };
  agentAssignmentRecords.unshift(rec);
  return rec;
}

/** 划拨：把机柜/站点挂到目标代理名下。任一资产不存在/已在目标名下 → 整批拒绝（不做半成功）。 */
export function assignAgentAssets(x: AssignAssetsPayload): AgentAssignmentRecord[] {
  const agent = agents.find((a) => a.agentNo === x.agentNo);
  if (!agent) throw new AgentAssignError(`代理商 ${x.agentNo ?? ""} 不存在`);
  if (agent.archivedAt) throw new AgentAssignError(`代理商 ${agent.agentNo} 已归档，不能作为划拨对象`);
  if (agent.status === "SUSPENDED") throw new AgentAssignError(`代理商 ${agent.agentNo} 已停用，先启用再划拨`);
  const cabinetNos = uniq(x.cabinetNos);
  const siteNos = uniq(x.siteNos);
  if (!cabinetNos.length && !siteNos.length) throw new AgentAssignError("请至少选择一台机柜或一个站点");

  const cabs = cabinetNos.map((no) => {
    const c = cabinets.find((y) => y.cabinetNo === no);
    if (!c) throw new AgentAssignError(`机柜 ${no} 不存在`);
    if (c.agentNo === agent.agentNo) throw new AgentAssignError(`机柜 ${no} 已归属 ${agent.agentNo}，无需重复划拨`);
    return c;
  });
  const sts = siteNos.map((no) => {
    const s = sites.find((y) => y.siteNo === no);
    if (!s) throw new AgentAssignError(`站点 ${no} 不存在`);
    if (s.agentNo === agent.agentNo) throw new AgentAssignError(`站点 ${no} 已归属 ${agent.agentNo}，无需重复划拨`);
    return s;
  });

  const out: AgentAssignmentRecord[] = [];
  for (const c of cabs) { c.agentNo = agent.agentNo; out.push(logAssignment(agent.agentNo, "CABINET", c.cabinetNo, "ASSIGN", x.operatorName)); }
  for (const s of sts) { s.agentNo = agent.agentNo; out.push(logAssignment(agent.agentNo, "SITE", s.siteNo, "ASSIGN", x.operatorName)); }
  refreshAgentAssetCounts();
  return out;
}

/** 回收：资产收回平台直营（agentNo 置 null）。流水记的是收回前的归属方。 */
export function reclaimAgentAssets(x: ReclaimAssetsPayload): AgentAssignmentRecord[] {
  const cabinetNos = uniq(x.cabinetNos);
  const siteNos = uniq(x.siteNos);
  if (!cabinetNos.length && !siteNos.length) throw new AgentAssignError("请至少选择一台机柜或一个站点");

  const cabs = cabinetNos.map((no) => {
    const c = cabinets.find((y) => y.cabinetNo === no);
    if (!c) throw new AgentAssignError(`机柜 ${no} 不存在`);
    if (!c.agentNo) throw new AgentAssignError(`机柜 ${no} 本就是平台直营，无需回收`);
    return c;
  });
  const sts = siteNos.map((no) => {
    const s = sites.find((y) => y.siteNo === no);
    if (!s) throw new AgentAssignError(`站点 ${no} 不存在`);
    if (!s.agentNo) throw new AgentAssignError(`站点 ${no} 本就是平台直营，无需回收`);
    return s;
  });

  const out: AgentAssignmentRecord[] = [];
  for (const c of cabs) { const from = c.agentNo!; c.agentNo = null; out.push(logAssignment(from, "CABINET", c.cabinetNo, "RECLAIM", x.operatorName)); }
  for (const s of sts) { const from = s.agentNo!; s.agentNo = null; out.push(logAssignment(from, "SITE", s.siteNo, "RECLAIM", x.operatorName)); }
  refreshAgentAssetCounts();
  return out;
}

// 种子归属就位后先对齐一次计数（否则首屏 agents.cabinetCount 全是占位的 0）
refreshAgentAssetCounts();
