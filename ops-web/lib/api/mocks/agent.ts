// 覆盖范围：代理商主档、区域分配、业绩、资金账户、分润规则。
import * as db from "../../mock/db";
import type { AgentApi } from "../contracts/agent";
import type { PageQ, ArchiveQ } from "../query";
import type { Agent } from "../../types";
import { wait } from "./_wait";

export const agentMock: AgentApi = {
  listAgents: (q: ArchiveQ = {}) =>
    wait(db.paginate(db.agents, q.page, q.size, (a) => db.liveHit(a, q.showArchived) && db.kwHit(q.keyword, a.name, a.agentNo, a.regionScope))),
  saveAgent: (a) => {
    const idx = db.agents.findIndex((x) => x.agentNo === a.agentNo);
    const merged = { ...(db.agents[idx] ?? { name: "", contact: "", regionScope: "", shareRate: 0.3, cabinetCount: 0, status: "ENABLED", archivedAt: null, agentNo: `AG${db.agents.length + 1}` }), ...a } as Agent;
    if (idx >= 0) db.agents[idx] = merged; else db.agents.push(merged);
    return wait(merged, 350);
  },

  // 代理商扩展
  listAgentAssignments: (q: PageQ = {}) => wait(db.listAgentAssignments(q)),
  listAgentPerformance: (q: PageQ = {}) => wait(db.listAgentPerformance(q)),
  listAgentAccounts: (q: PageQ = {}) => wait(db.listAgentAccounts(q)),
  saveAgentAccount: (x) => wait(db.saveAgentAccount(x), 350),

  // 代理分润
  listAgentCommissions: (q: PageQ = {}) => wait(db.listAgentCommissions(q)),
  saveAgentCommission: (x) => wait(db.saveAgentCommission(x), 350),

  // G1 软删除：归档 / 恢复（禁止物理删除）
  archiveAgent: async (no) => wait(db.archiveAgent(no), 350),
  unarchiveAgent: async (no) => wait(db.unarchiveAgent(no), 350),
};
