// 覆盖范围：代理商主档、区域分配、业绩、资金账户、分润规则。
import type { PageQ, ArchiveQ } from "../query";
import type {
  PageResult, Agent, AgentAssignment, AgentPerformance, AgentAccount, AgentCommission,
} from "../../types";

export interface AgentApi {
  listAgents(q?: ArchiveQ): Promise<PageResult<Agent>>;
  saveAgent(a: Partial<Agent> & { agentNo?: string }): Promise<Agent>;

  // === 代理商扩展 tab ===
  listAgentAssignments(q?: PageQ): Promise<PageResult<AgentAssignment>>;
  listAgentPerformance(q?: PageQ): Promise<PageResult<AgentPerformance>>;
  listAgentAccounts(q?: PageQ): Promise<PageResult<AgentAccount>>;
  saveAgentAccount(x: Partial<AgentAccount> & { accountNo?: string }): Promise<AgentAccount>;

  // === 代理分润 ===
  listAgentCommissions(q?: PageQ): Promise<PageResult<AgentCommission>>;
  saveAgentCommission(x: Partial<AgentCommission> & { ruleNo?: string }): Promise<AgentCommission>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  archiveAgent(agentNo: string): Promise<Agent>;
  unarchiveAgent(agentNo: string): Promise<Agent>;
}
