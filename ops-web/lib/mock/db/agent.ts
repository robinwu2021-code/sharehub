// 代理商域：代理商档案 agents / 区域分配 agentAssignments / 业绩排名 agentPerformances /
// 代理账号 agentAccounts / 佣金规则 agentCommissions。
// agents 是全库的“代理商来源”，分润统计（finance.ts）等域一律引用它。
import type {
  Agent, AgentAssignment, AgentPerformance, AgentAccount, AgentCommission,
  AgentAssignmentRecord, AssignableAsset, AssignAssetsPayload, ReclaimAssetsPayload,
  AssetType, AssignAction, PageQuery, SiteAgent,
} from "../../types";
import { p, iso, phone, OPERATORS } from "./internal";
import { paginate, kwHit, upsert, nextNo, archiveRow, unarchiveRow } from "./helpers";
import { cabinets } from "./device";
import { sites } from "./location";
import { fail, notFound } from "@/lib/biz-error";

export const agents: Agent[] = Array.from({ length: 9 }, (_, i) => ({
  agentNo: `AG${String(i + 1).padStart(3, "0")}`, name: p(["North Hub", "Marina Partner", "Deira Agent", "Airport Ops", "JBR Franchise"], i),
  contact: `+9715${String(6000000 + i * 271).slice(0, 7)}`, regionScope: p(["Dubai North", "Dubai Marina", "Deira", "DXB", "JBR"], i),
  // cabinetCount 由 refreshAgentAssetCounts() 从 cabinets.agentNo 实时反算（下方立即调用一次），
  // 这里给 0 只是占位——档案页的「设备数」与划拨页必须是同一个数。
  // 两种登记类型都出现，页面上能看出区别（ADR-027）
  agentType: i % 3 === 0 ? "CITY_PARTNER" : "AGENT",
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

/**
 * 划拨流水（审计）。种子取「当前确实归属某代理」的前几项资产，保证流水与归属现状不矛盾。
 *
 * ⚠️ **惰性初始化，不能在模块顶层就地求值**。
 *
 * 它读的 `cabinets` / `sites` 来自 device / location 两个模块，而那两个模块（直接或间接）
 * 又会 import 本模块 —— 只要这条环存在，本模块被先求值时 `cabinets` 就是 `undefined`，
 * 表现是 `Cannot read properties of undefined (reading 'filter')`，**整批 mock 测试全挂**，
 * 而错误信息完全看不出真正的成因是「谁 import 了谁」。
 *
 * 2026-09-23 实测：location 新增一行 `import { agents } from "./agent"` 就闭合了
 * `device → location → agent → device`，当场炸掉 20 余个测试文件。
 * 改成 getter 之后，求值推迟到第一次读取，那时三个模块都已初始化完 —— 环还在，但不再致命。
 */
let _assignmentRecords: AgentAssignmentRecord[] | null = null;
function buildAssignmentRecords(): AgentAssignmentRecord[] {
  return [
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
}

/**
 * 用 getter 而不是函数，是为了**不改调用方**：现有 13 处都是当数组用的
 * （`db.agentAssignmentRecords.filter(...)`），改成函数要逐处加括号。
 */
export const agentAssignmentRecords: AgentAssignmentRecord[] = new Proxy([] as AgentAssignmentRecord[], {
  /*
   * ⚠️ **五个 trap 一个都不能少**，尤其是 set。
   * 少了 set 的话，`push` 写的是那个空的 target 而不是真数组 ——
   * 读得到种子、却读不到刚写进去的划拨流水，而且不报任何错。
   * （实测：只写 get/has/ownKeys 时，assign.test.ts 的 4 条断言全挂在这上面。）
   */
  get(_t, prop, recv) {
    _assignmentRecords ??= buildAssignmentRecords();
    return Reflect.get(_assignmentRecords, prop, recv);
  },
  set(_t, prop, value) {
    _assignmentRecords ??= buildAssignmentRecords();
    return Reflect.set(_assignmentRecords, prop, value);
  },
  deleteProperty(_t, prop) {
    _assignmentRecords ??= buildAssignmentRecords();
    return Reflect.deleteProperty(_assignmentRecords, prop);
  },
  has(_t, prop) {
    _assignmentRecords ??= buildAssignmentRecords();
    return Reflect.has(_assignmentRecords, prop);
  },
  ownKeys() {
    _assignmentRecords ??= buildAssignmentRecords();
    return Reflect.ownKeys(_assignmentRecords);
  },
  getOwnPropertyDescriptor(_t, prop) {
    _assignmentRecords ??= buildAssignmentRecords();
    return Reflect.getOwnPropertyDescriptor(_assignmentRecords, prop);
  },
});

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

/**
 * 站点上的伙伴责任（ADR-027 §三）。
 *
 * ⚠️ 放在 agent.ts 而不是 location.ts：本模块**本来就**依赖 `./location` 的 sites，
 * 反过来让 location 依赖 agents 会成环 —— 实测症状是模块初始化时对方还是 undefined，
 * 报一句 `Cannot read properties of undefined (reading 'filter')`，
 * 而堆栈指向一个与本次改动无关的测试文件，极难定位。依赖方向只能有一个。
 *
 * 种子刻意铺出「A 牵线、B 经营」与「同一人既出资又运维」两种形态 ——
 * 这正是 ADR 说「开站一个月内就会出现」的那两种，页面上要能看见。
 */
export const siteAgents: SiteAgent[] = [];

/**
 * 种子**惰性铺**，不在模块顶层碰 `sites`。
 *
 * 顶层直接 `sites.filter(...)` 会在模块初始化期跨模块取值；本目录里 agent ↔ device ↔ location
 * 之间已有依赖环，实测报的是
 * `'get' on proxy: property '0' is a read-only and non-configurable data property` ——
 * 而堆栈指向一个与本次改动毫无关系的测试文件（划拨），极难定位。
 * 推迟到第一次真正用的时候，环就不在初始化期上了。
 */
let seeded = false;
function ensureSeed() {
  if (seeded) return;
  seeded = true;
  const withAgent = sites.filter((s) => s.agentNo).slice(0, 6);
  withAgent.forEach((s, i) => siteAgents.push({
    id: 100 + i, siteNo: s.siteNo, agentNo: s.agentNo!, role: "OPERATE",
    remark: "由站点归属回填", ruleNo: null, effectiveFrom: null, effectiveTo: null,
  }));
  // 刻意铺出 ADR 说「开站一个月内就会出现」的两种形态，页面上要能看见
  siteAgents.push(
    // 同一站点两个伙伴：A 出资、B 运维
    { id: 200, siteNo: sites[1].siteNo, agentNo: "AG005", role: "INVEST",
      remark: "出资方，只分资产收益", ruleNo: null, effectiveFrom: null, effectiveTo: null },
    // 牵线：只介绍关系，不谈判；介绍费签约时一次性付，不进逐单分润
    { id: 201, siteNo: sites[2].siteNo, agentNo: "AG008", role: "REFER",
      remark: "介绍商场招商负责人", ruleNo: null, effectiveFrom: null, effectiveTo: null },
    // 拓展：找场地、谈判、签合同
    { id: 202, siteNo: sites[3].siteNo, agentNo: "AG002", role: "DEVELOP",
      remark: "全程谈下进场合同", ruleNo: null, effectiveFrom: null, effectiveTo: null },
  );
}

export const listSiteAgents = (siteNo: string) => {
  ensureSeed();
  const ORDER: SiteAgent["role"][] = ["INVEST", "DEVELOP", "OPERATE", "REFER"];
  return siteAgents.filter((x) => x.siteNo === siteNo)
    .map((x) => ({ ...x, agentName: agents.find((a) => a.agentNo === x.agentNo)?.name ?? null }))
    // 按责任层序排，不按 id —— 同一个站点两次打开顺序要一致
    .sort((a, b) => ORDER.indexOf(a.role) - ORDER.indexOf(b.role));
};

export const saveSiteAgent = (siteNo: string, x: Partial<SiteAgent>) => {
  ensureSeed();
  if (!x.agentNo) fail("请选择合作伙伴", "Partner is required");
  const role = x.role ?? "OPERATE";
  // 悬空的 agentNo 会让分润按一个不存在的受益方生成记录，而且不报错
  const agent = agents.find((a) => a.agentNo === x.agentNo);
  if (!agent) notFound("合作伙伴", "Partner", x.agentNo!);
  if (x.effectiveFrom && x.effectiveTo && x.effectiveTo < x.effectiveFrom) {
    fail("生效止不能早于生效起", "Effective end must not precede start");
  }
  const LABEL: Record<string, string> = { INVEST: "出资", DEVELOP: "拓展", OPERATE: "运维", REFER: "牵线" };
  for (const e of siteAgents.filter((a) => a.siteNo === siteNo && a.agentNo === x.agentNo)) {
    if (x.id != null && e.id === x.id) continue;
    if (e.role === role) {
      fail(`${agent!.name} 在本站点已经有「${LABEL[role]}」这条责任了，直接改那一行即可。`,
        `${agent!.name} already has the "${role}" role on this site.`);
    }
    // 牵线是拓展的弱形式，同一人同一站点只能算其一——否则同一件事付两份钱
    const xor = (a: string, b: string) => (a === "REFER" && b === "DEVELOP") || (a === "DEVELOP" && b === "REFER");
    if (xor(role, e.role)) {
      fail(`「${LABEL[role]}」与「${LABEL[e.role]}」不能并存：牵线是拓展的弱形式，同一个人在同一个站点只能算其一。`,
        `"${role}" conflicts with "${e.role}" for the same partner on the same site.`);
    }
  }
  const row: SiteAgent = {
    id: x.id ?? Math.max(0, ...siteAgents.map((a) => a.id ?? 0)) + 1,
    siteNo, agentNo: x.agentNo!, role,
    ruleNo: x.ruleNo ?? null,
    effectiveFrom: x.effectiveFrom ?? null, effectiveTo: x.effectiveTo ?? null,
    remark: x.remark ?? "",
  };
  const at = siteAgents.findIndex((a) => a.id === row.id);
  if (at >= 0) siteAgents[at] = row; else siteAgents.push(row);
  return { ...row, agentName: agent!.name };
};

export const removeSiteAgent = (siteNo: string, id: number) => {
  ensureSeed();
  const at = siteAgents.findIndex((a) => a.id === id && a.siteNo === siteNo);
  if (at < 0) notFound("责任行", "Site partner role", String(id));
  siteAgents.splice(at, 1);
  return { ok: true };
};
