// 覆盖范围：代理商主档、区域分配、业绩、资金账户、分润规则。
// 端点前缀：/api/agent/**
import { client } from "../http-client";
import type { AgentApi } from "../contracts/agent";
import type { PageQ, ArchiveQ, AssignmentRecordQ, AssignableAssetQ } from "../query";

export const agentHttp: AgentApi = {
  listAgents: (q?: ArchiveQ) => client.get("/api/agent/agents", q),
  saveAgent: (a) => client.post(a.agentNo ? `/api/agent/agents/${a.agentNo}` : "/api/agent/agents", a),

  // 代理商扩展
  listAgentAssignments: (q?: PageQ) => client.get("/api/agent/assignments", q),
  listAgentPerformance: (q?: PageQ) => client.get("/api/agent/performance", q),
  listAgentAccounts: (q?: PageQ) => client.get("/api/agent/accounts", q),
  saveAgentAccount: (x) => client.post(x.accountNo ? `/api/agent/accounts/${x.accountNo}` : "/api/agent/accounts", x),

  // S1 设备/点位划拨：划拨/回收是「归属状态迁移」，用 POST 动作端点而非 PUT 整体覆盖
  listAssignableAssets: (q?: AssignableAssetQ) => client.get("/api/agent/assignable-assets", q),
  assignAgentAssets: (x) => client.post("/api/agent/assignments/assign", x),
  reclaimAgentAssets: (x) => client.post("/api/agent/assignments/reclaim", x),
  listAgentAssignmentRecords: (q?: AssignmentRecordQ) => client.get("/api/agent/assignment-records", q),

  // 代理分润
  listAgentCommissions: (q?: PageQ) => client.get("/api/agent/commissions", q),
  saveAgentCommission: (x) => client.post(x.ruleNo ? `/api/agent/commissions/${x.ruleNo}` : "/api/agent/commissions", x),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  archiveAgent: (no) => client.post(`/api/agent/agents/${no}/archive`, {}),
  unarchiveAgent: (no) => client.post(`/api/agent/agents/${no}/unarchive`, {}),
};
