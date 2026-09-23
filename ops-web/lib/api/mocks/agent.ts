// 覆盖范围：代理商主档、区域分配、业绩、资金账户、分润规则。
import * as db from "../../mock/db";
import type { AgentApi } from "../contracts/agent";
import type { PageQ, ArchiveQ, AssignmentRecordQ, AssignableAssetQ , ReportQ } from "../query";
import type { Agent } from "../../types";
import { wait } from "./_wait";

export const agentMock: AgentApi = {
  listAgents: (q: ArchiveQ = {}) => {
    // 设备数是从 cabinets.agentNo 反算的：查询前先对齐，否则划拨后档案页的「设备数」还是旧值
    db.refreshAgentAssetCounts();
    return wait(db.paginate(db.agents, q.page, q.size, (a) => db.liveHit(a, q.showArchived) && db.kwHit(q.keyword, a.name, a.agentNo, a.regionScope)));
  },
  saveAgent: (a) => {
    const idx = db.agents.findIndex((x) => x.agentNo === a.agentNo);
    const merged = { ...(db.agents[idx] ?? { name: "", contact: "", regionScope: "", shareRate: 0.3, cabinetCount: 0, status: "ENABLED", archivedAt: null, agentNo: `AG${db.agents.length + 1}` }), ...a } as Agent;
    if (idx >= 0) db.agents[idx] = merged; else db.agents.push(merged);
    return wait(merged, 350);
  },

  // 代理商扩展
  listAgentAssignments: (q: PageQ = {}) => wait(db.listAgentAssignments(q)),
  listAgentPerformance: (q: ReportQ = {}) => wait(db.listAgentPerformance(q)),
  listAgentAccounts: (q: PageQ = {}) => wait(db.listAgentAccounts(q)),
  saveAgentAccount: (x) => wait(db.saveAgentAccount(x), 350),

  // 入驻申请：写操作全部走 db 层 —— 状态机与「同手机号至多一张在途」都在那强制
  listAgentApplies: (q = {}) => wait(db.listAgentApplies(q)),
  acceptAgentApply: (applyNo, operatorName) => wait(db.acceptAgentApply(applyNo, operatorName), 350),
  auditAgentApply: (x) => wait(db.auditAgentApply(x), 400),
  createAgentApply: (x) => wait(db.createAgentApply(x), 400),
  // 自助注册（公开页）：与代建落同一份数据，只是 source 不同
  sendApplyOtp: (phone) => wait(db.sendApplyOtp(phone), 300),
  selfServiceApply: (x) => wait(db.selfServiceApply(x), 400),
  myApply: (phone, otp) => wait(db.myApply(phone, otp), 300),

  // S1 设备/点位划拨：写操作一律走 db 层的 assign/reclaim（校验 + 落流水 + 刷新汇总都在那）
  listAssignableAssets: (q: AssignableAssetQ = {}) => wait(db.listAssignableAssets(q)),
  assignAgentAssets: (x) => wait(db.assignAgentAssets(x), 400),
  reclaimAgentAssets: (x) => wait(db.reclaimAgentAssets(x), 400),
  listAgentAssignmentRecords: (q: AssignmentRecordQ = {}) => wait(db.listAgentAssignmentRecords(q)),

  // 代理分润
  listAgentCommissions: (q: PageQ = {}) => wait(db.listAgentCommissions(q)),
  saveAgentCommission: (x) => wait(db.saveAgentCommission(x), 350),

  // G1 软删除：归档 / 恢复（禁止物理删除）
  archiveAgent: async (no) => wait(db.archiveAgent(no), 350),
  unarchiveAgent: async (no) => wait(db.unarchiveAgent(no), 350),
};
