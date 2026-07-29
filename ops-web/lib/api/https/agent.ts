// 覆盖范围：代理商主档、区域分配、业绩、资金账户、分润规则。
// 端点前缀：/api/agent/**
import { client } from "../http-client";
import type { AgentApi } from "../contracts/agent";
import type { PageQ } from "../query";

export const agentHttp: AgentApi = {
  listAgents: (q?: PageQ) => client.get("/api/agent/agents", q),
  saveAgent: (a) => client.post(a.agentNo ? `/api/agent/agents/${a.agentNo}` : "/api/agent/agents", a),

  // 代理商扩展
  listAgentAssignments: (q?: PageQ) => client.get("/api/agent/assignments", q),
  listAgentPerformance: (q?: PageQ) => client.get("/api/agent/performance", q),
  listAgentAccounts: (q?: PageQ) => client.get("/api/agent/accounts", q),
  saveAgentAccount: (x) => client.post(x.accountNo ? `/api/agent/accounts/${x.accountNo}` : "/api/agent/accounts", x),

  // 代理分润
  listAgentCommissions: (q?: PageQ) => client.get("/api/agent/commissions", q),
  saveAgentCommission: (x) => client.post(x.ruleNo ? `/api/agent/commissions/${x.ruleNo}` : "/api/agent/commissions", x),
};
