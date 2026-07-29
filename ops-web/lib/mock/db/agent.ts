// 代理商域：代理商档案 agents / 区域分配 agentAssignments / 业绩排名 agentPerformances /
// 代理账号 agentAccounts / 佣金规则 agentCommissions。
// agents 是全库的“代理商来源”，分润统计（finance.ts）等域一律引用它。
import type {
  Agent, AgentAssignment, AgentPerformance, AgentAccount, AgentCommission, PageQuery,
} from "../../types";
import { p, iso, phone } from "./internal";
import { paginate, kwHit, upsert, nextNo } from "./helpers";

export const agents: Agent[] = Array.from({ length: 9 }, (_, i) => ({
  agentNo: `AG${String(i + 1).padStart(3, "0")}`, name: p(["North Hub", "Marina Partner", "Deira Agent", "Airport Ops", "JBR Franchise"], i),
  contact: `+9715${String(6000000 + i * 271).slice(0, 7)}`, regionScope: p(["Dubai North", "Dubai Marina", "Deira", "DXB", "JBR"], i),
  shareRate: [0.3, 0.35, 0.4][i % 3], cabinetCount: 4 + i * 3, status: i % 6 === 0 ? "SUSPENDED" : "ENABLED",
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
    status: i % 6 === 0 ? "DISABLED" : "ACTIVE", dataScope: p(["本代理数据", "区域数据", "指定站点"], i),
    createdAt: iso(i * 86400_000),
  };
});

export const agentCommissions: AgentCommission[] = [
  { ruleNo: "AC0001", agentNo: "AGT001", agentName: "Dubai South Agency", dimension: "GMV", rate: 0.12, mode: "CHANNEL_SPLIT", effectiveAt: "2026-01-01", status: "ACTIVE" },
  { ruleNo: "AC0002", agentNo: "AGT002", agentName: "Abu Dhabi Partners", dimension: "GMV", rate: 0.10, mode: "LEDGER", effectiveAt: "2026-01-01", status: "ACTIVE" },
  { ruleNo: "AC0003", agentNo: "AGT003", agentName: "Sharjah Ops", dimension: "ORDER_COUNT", rate: 0.08, mode: "LEDGER", effectiveAt: "2026-03-01", status: "INACTIVE" },
];

export const listAgentAssignments = (q: PageQuery = {}) => paginate(agentAssignments, q.page, q.size, (x) => kwHit(q.keyword, x.agentNo, x.agentName, x.region));
export const listAgentPerformance = (q: PageQuery = {}) => paginate(agentPerformances, q.page, q.size, (x) => kwHit(q.keyword, x.agentNo, x.agentName));
export const listAgentAccounts = (q: PageQuery = {}) => paginate(agentAccounts, q.page, q.size, (x) => kwHit(q.keyword, x.accountNo, x.agentNo, x.agentName, x.loginPhone));
export const listAgentCommissions = (q: PageQuery = {}) => paginate(agentCommissions, q.page, q.size, (x) => kwHit(q.keyword, x.ruleNo, x.agentNo, x.agentName));

export const saveAgentAccount = (x: Partial<AgentAccount>) => upsert(agentAccounts, x, "accountNo", () => nextNo("AA", agentAccounts));
export const saveAgentCommission = (x: Partial<AgentCommission>) => upsert(agentCommissions, x, "ruleNo", () => nextNo("AC", agentCommissions));
