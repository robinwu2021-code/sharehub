// 代理商域：代理商档案 agents / 区域分配 agentAssignments / 业绩排名 agentPerformances /
// 代理账号 agentAccounts / 佣金规则 agentCommissions。
// agents 是全库的“代理商来源”，分润统计（finance.ts）等域一律引用它。
import type {
  Agent, AgentAssignment, AgentPerformance, AgentAccount, AgentCommission, PageQuery,
} from "../../types";
import { p, iso, phone } from "./internal";
import { paginate, kwHit, upsert, nextNo, archiveRow, unarchiveRow } from "./helpers";

export const agents: Agent[] = Array.from({ length: 9 }, (_, i) => ({
  agentNo: `AG${String(i + 1).padStart(3, "0")}`, name: p(["North Hub", "Marina Partner", "Deira Agent", "Airport Ops", "JBR Franchise"], i),
  contact: `+9715${String(6000000 + i * 271).slice(0, 7)}`, regionScope: p(["Dubai North", "Dubai Marina", "Deira", "DXB", "JBR"], i),
  shareRate: [0.3, 0.35, 0.4][i % 3], cabinetCount: 4 + i * 3, status: i % 6 === 0 ? "SUSPENDED" : "ENABLED",
  archivedAt: null,
}));

export const agentAssignments: AgentAssignment[] = agents.map((a, i) => ({
  agentNo: a.agentNo, agentName: a.name, region: a.regionScope,
  cabinetCount: a.cabinetCount, siteCount: 1 + (i * 2) % 8,
}));
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

export const listAgentAssignments = (q: PageQuery = {}) => paginate(agentAssignments, q.page, q.size, (x) => kwHit(q.keyword, x.agentNo, x.agentName, x.region));
export const listAgentPerformance = (q: PageQuery = {}) => paginate(agentPerformances, q.page, q.size, (x) => kwHit(q.keyword, x.agentNo, x.agentName));
export const listAgentAccounts = (q: PageQuery = {}) => paginate(agentAccounts, q.page, q.size, (x) => kwHit(q.keyword, x.accountNo, x.agentNo, x.agentName, x.loginPhone));
export const listAgentCommissions = (q: PageQuery = {}) => paginate(agentCommissions, q.page, q.size, (x) => kwHit(q.keyword, x.ruleNo, x.agentNo, x.agentName));

export const saveAgentAccount = (x: Partial<AgentAccount>) => upsert(agentAccounts, x, "accountNo", () => nextNo("AA", agentAccounts));
export const saveAgentCommission = (x: Partial<AgentCommission>) => upsert(agentCommissions, x, "ruleNo", () => nextNo("AC", agentCommissions));

// —— G1 软删除：代理商档案 ——
export const archiveAgent = (no: string) => archiveRow(agents, "agentNo", no);
export const unarchiveAgent = (no: string) => unarchiveRow(agents, "agentNo", no);
