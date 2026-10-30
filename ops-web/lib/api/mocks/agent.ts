// 覆盖范围：代理商主档、区域分配、业绩、资金账户、分润规则。
import * as db from "../../mock/db";
import * as ax from "../../mock/db/agent-exit";
import * as dp from "../../mock/db/device-pool";
import { notFound } from "@/lib/biz-error";
import type { AgentApi } from "../contracts/agent";
import type { PageQ, ArchiveQ, AssignmentRecordQ, AssignableAssetQ , ReportQ } from "../query";
import type { Agent } from "../../types";
import { wait } from "./_wait";
import { currentAuth } from "../../auth";
import { fail } from "../../biz-error";
import type { MerchantProfile } from "../../types";

/** 商家号取自会话（与后端同：不接受入参）；运营会话没有商家号 → 403 语义。 */
function myAgent(): Agent {
  const no = currentAuth()?.currentOperatorNo;
  const a = no ? db.agents.find((x) => x.agentNo === no) : undefined;
  if (!a) fail("只有商家会话可以查看或修改商家资料", "Only a merchant session can view or edit the merchant profile");
  return a;
}
const toProfile = (a: Agent): MerchantProfile => ({
  agentNo: a.agentNo, name: a.name, address: a.address ?? null, lng: a.lng ?? null, lat: a.lat ?? null,
  industry: a.industry ?? null, contactName: a.contactName ?? null, phone: a.phone ?? null, email: a.email ?? null,
  defaultSiteNo: a.defaultSiteNo ?? null, status: a.status,
});

export const agentMock: AgentApi = {
  listAgents: (q: ArchiveQ = {}) => {
    // 设备数是从 cabinets.agentNo 反算的：查询前先对齐，否则划拨后档案页的「设备数」还是旧值
    db.refreshAgentAssetCounts();
    // 资料齐全的商家都该有默认站点（后端 8271deaa 补齐资料即建）；mock 在第一次读商家时补上，
    // 否则整页刷新后商家详情会显示「还没有默认站点」（mock 状态随页面重载重置）
    dp.ensurePoolSeed();
    return wait(db.paginate(db.agents, q.page, q.size, (a) => db.liveHit(a, q.showArchived) && db.kwHit(q.keyword, a.name, a.agentNo, a.regionScope)));
  },
  saveAgent: (a) => {
    /*
     * 编号为空 = 新建，**服务端取号**（与真后端同口径：请求体里的 agentNo 不作数）。
     * 原先取的是 `AG${length + 1}` —— 那正是 helpers 里 nextNo 的注释点名的撞号写法：
     * 种子从 AG001 起共 8 条，删一条再建就会撞已有号。
     *
     * mock 的号从 900 起（nextNo 的默认 base），**刻意与种子号段错开** ——
     * 所以它长得不像真后端出的 AG009，这是设计如此，不是分叉。
     */
    const no = a.agentNo?.trim() || db.nextNo("AG", db.agents);
    const idx = db.agents.findIndex((x) => x.agentNo === no);
    const before = idx >= 0 ? db.agents[idx].status : null;
    db.checkAgentCoreProfile(a);
    const prev = idx >= 0 ? db.agents[idx] : null;
    const merged = { ...(db.agents[idx] ?? { name: "", contact: "", regionScope: "", shareRate: 0.3, cabinetCount: 0, status: "ENABLED", archivedAt: null }), ...a, agentNo: no } as Agent;
    // 新建的排最上（与场地方同口径，也与真后端的倒序一致）
    if (idx >= 0) db.agents[idx] = merged; else db.agents.unshift(merged);
    // 默认站点只由服务端定，请求体里的不作数
    merged.defaultSiteNo = prev?.defaultSiteNo ?? null;
    db.syncDefaultSite(merged, !prev || prev.name !== merged.name || prev.address !== merged.address
      || prev.lng !== merged.lng || prev.lat !== merged.lat);
    // 停用联动（F2）：名下未完结工单改派平台 —— 后端由 AgentStatusChangedEvent 的监听器做
    if (before && before !== "SUSPENDED" && merged.status === "SUSPENDED") ax.onAgentSuspended(merged.agentNo);
    return wait(merged, 350);
  },

  grantAgentLogin: (no) => wait(db.grantAgentLogin(no), 350),

  // 代理商扩展
  listAgentAssignments: (q: PageQ = {}) => wait(db.listAgentAssignments(q)),
  listAgentPerformance: (q: ReportQ = {}) => wait(db.listAgentPerformance(q)),
  listAgentAccounts: (q: PageQ = {}) => wait(db.listAgentAccounts(q)),
  saveAgentAccount: (x) => wait(db.saveAgentAccount(x), 350),

  // 入驻申请：写操作全部走 db 层 —— 状态机与「同手机号至多一张在途」都在那强制
  listAgentApplies: (q = {}) => wait(db.listAgentApplies(q)),
  acceptAgentApply: (applyNo, operatorName) => wait(db.acceptAgentApply(applyNo, operatorName), 350),
  auditAgentApply: (x) => wait(db.auditAgentApply(x), 400),
  resendAgentInvite: async (agentNo) => wait(db.resendAgentInvite(agentNo), 400),
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

  getAgentAccount: async (no) => wait(db.agentAccounts.find((x) => x.accountNo === no) ?? notFound("代理账号", "Agent account", no)),
  getMyProfile: async () => wait(toProfile(myAgent())),
  updateMyContact: async (x) => {
    const a = myAgent();
    if (!x.contactName?.trim()) fail("联系人姓名必填", "Contact name is required");
    Object.assign(a, { contactName: x.contactName.trim(), phone: x.phone?.trim() || null, email: x.email?.trim().toLowerCase() || null });
    return wait(toProfile(a), 300);
  },
  getAgentCommission: async (no) => wait(db.agentCommissions.find((x) => x.ruleNo === no) ?? notFound("分润规则", "Commission rule", no)),

  // 代理清退 / 运维考核：状态机与门禁在 db 层（agent-exit.ts）强制；async 让抛错变成 rejected promise
  startAgentExit: async (agentNo, reason) => wait(ax.startAgentExit(agentNo, reason), 400),
  getAgentExit: async (no) => wait(ax.getAgentExit(no)),
  getAgentOpenExit: async (agentNo) => wait(ax.getAgentOpenExit(agentNo)),
  agentExitGate: async (no) => wait(ax.agentExitGate(no)),
  advanceAgentExit: async (no) => wait(ax.advanceAgentExit(no), 400),
  listAgentOpsAssessments: async (agentNo) => wait(ax.listAgentOpsAssessments(agentNo)),

  // G1 软删除：归档 / 恢复（禁止物理删除）
  archiveAgent: async (no) => wait(db.archiveAgent(no), 350),
  unarchiveAgent: async (no) => wait(db.unarchiveAgent(no), 350),
};
