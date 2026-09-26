// 覆盖范围：代理商主档、区域分配、业绩、资金账户、分润规则。
// 端点前缀：/api/agent/**
import { client } from "../http-client";
import type { AgentApi } from "../contracts/agent";
import type { PageQ, ArchiveQ, AssignmentRecordQ, AssignableAssetQ , ReportQ } from "../query";
import type { AgentAssignmentRecord, AssetType, AssignAction, AssignableAsset } from "../../types/agent";
import type { PageResult } from "../../types/common";

/**
 * 后端 `agt_assignment` 流水行（AgentExtDtos.AssignmentLog）原样形状。
 * 与前端 {@link AgentAssignmentRecord} 字段名成对但不同名，故此处显式声明 + 映射，
 * 不用 `as` 硬转 —— 硬转会让 assignNo/targetNo 静默变成 undefined。
 */
interface AssignmentLogRaw {
  assignNo: string;
  agentNo: string;
  targetType: string; // CABINET | LOCATION | SITE
  targetNo: string;
  action: string; // ASSIGN | REVOKE
  operator: string;
  createdAt: string;
}

/** 后端流水行 → 前端划拨记录。 */
function toAssignmentRecord(r: AssignmentLogRaw): AgentAssignmentRecord {
  return {
    assignmentNo: r.assignNo,
    agentNo: r.agentNo,
    // ⚠️ 后端 AssignmentLog 不带代理名（只存 agentNo，未 join agt_agent）。
    //    表格里这一列会空 —— 需后端补 join，或页面用已加载的代理列表本地映射。
    agentName: "",
    // 后端 LOCATION 前端无对应枚举（AssetType 只有 CABINET|SITE），归到 SITE 展示。
    assetType: (r.targetType === "CABINET" ? "CABINET" : "SITE") as AssetType,
    assetNo: r.targetNo,
    action: (r.action === "REVOKE" ? "RECLAIM" : "ASSIGN") as AssignAction,
    operatorName: r.operator,
    createdAt: r.createdAt,
  };
}

export const agentHttp: AgentApi = {
  listAgents: (q?: ArchiveQ) => client.get("/api/agent/agents", q),
  saveAgent: (a) => client.post(a.agentNo ? `/api/agent/agents/${a.agentNo}` : "/api/agent/agents", a),

  // 代理商扩展
  listAgentAssignments: (q?: PageQ) => client.get("/api/agent/assignments", q),
  listAgentPerformance: (q?: ReportQ) => client.get("/api/agent/performance", q),
  listAgentAccounts: (q?: PageQ) => client.get("/api/agent/accounts", q),
  saveAgentAccount: (x) => client.post(x.accountNo ? `/api/agent/accounts/${x.accountNo}` : "/api/agent/accounts", x),

  // S1 设备/点位划拨。
  // ⚠️ T1-D 后端缺口：可划拨资产池无端点（AgentExtController 只有 /assignments）。
  // 入驻申请（ADR-030 §三）。注意 source 不从前端传 —— 服务端按有无 STAFF 令牌判定
  listAgentApplies: (q) => client.get("/api/agent/applies", q),
  acceptAgentApply: (applyNo) => client.post(`/api/agent/applies/${applyNo}/accept`, {}),
  auditAgentApply: ({ applyNo, ...body }) => client.post(`/api/agent/applies/${applyNo}/audit`, body),
  // 自助注册三件套：全部免鉴权，防刷靠 OTP + 单 IP 限流 + 同手机号至多一张在途
  sendApplyOtp: (phone) => client.post("/api/agent/apply/otp", { phone }),
  selfServiceApply: (x) => client.post("/api/agent/apply", x),
  myApply: (phone, otp) => client.get("/api/agent/apply/mine", { phone, otp }),

  // 代建走复数端点（判 agent:apply:create）；单数 /apply 是免鉴权的自助入口
  createAgentApply: (x) => client.post("/api/agent/applies", x),

  // 2026-09-26 起后端返回统一的 PageResult 并自己认 excludeAgentNo（§5.8 #4），
  // 所以这里直接透传 —— 此前那层「裸数组包成 PageResult + 前端过滤 excludeAgentNo」已撤掉。
  // ⚠️ total 是**候选池（前 500 条）里的条数**，不是全表 count：候选池是选项源，
  // 翻到底就是底，不会出现「总数 800 却翻到第 6 页就空了」。
  listAssignableAssets: (q?: AssignableAssetQ) =>
    client.get<PageResult<AssignableAsset>>("/api/agent/assignable-assets", q),

  // T0-5：后端是**单资产**端点 POST /api/agent/assignments（AssignReq{agentNo,targetType,
  // targetNo,action,operator} → AssignmentLog），前端契约是**批量**。此处做扇出适配。
  //
  // ⚠️ 非原子：后端没有批量端点，N 个资产 = N 次请求，中途失败会留下"部分划拨"。
  //    types/agent.ts 的注释写着「后端一个事务」——那是 mock 的行为，真实后端做不到。
  //    要恢复原子性需后端补 POST /assignments/batch。
  assignAgentAssets: async (x) => {
    const targets = [
      ...x.cabinetNos.map((no) => ({ targetType: "CABINET", targetNo: no })),
      ...x.siteNos.map((no) => ({ targetType: "SITE", targetNo: no })),
    ];
    const logs = await Promise.all(
      targets.map((t) =>
        client.post<AssignmentLogRaw>("/api/agent/assignments", {
          agentNo: x.agentNo, ...t, action: "ASSIGN", operator: x.operatorName,
        }),
      ),
    );
    return logs.map(toAssignmentRecord);
  },

  // ⚠️ T1-D 未接通，仍指向不存在的端点：后端 assign() 强制校验 agentNo 必填且代理必须存在，
  //    而 ReclaimAssetsPayload 故意不带 agentNo（「从资产当前归属反查」，见 types/agent.ts）。
  //    两边语义冲突，不能靠改路径解决 —— 需二选一：后端支持 agentNo 缺省时反查归属，
  //    或前端回收抽屉改为按代理维度提交并带上 agentNo。留待拍板，不在此处猜。
  //    注：后端动作枚举是 ASSIGN|REVOKE，前端 AssignAction 是 ASSIGN|RECLAIM，一并需对齐。
  reclaimAgentAssets: (x) => client.post("/api/agent/assignments/reclaim", x),

  // T0-5：划拨流水复用 /assignments，靠 ?view=log 切读模型（见 AgentExtController#assignments）。
  listAgentAssignmentRecords: async (q?: AssignmentRecordQ) => {
    const p = await client.get<PageResult<AssignmentLogRaw>>(
      "/api/agent/assignments", { ...q, view: "log" },
    );
    return { ...p, list: (p.list ?? []).map(toAssignmentRecord) };
  },

  // 代理分润
  listAgentCommissions: (q?: PageQ) => client.get("/api/agent/commissions", q),
  saveAgentCommission: (x) => client.post(x.ruleNo ? `/api/agent/commissions/${x.ruleNo}` : "/api/agent/commissions", x),

  getAgentAccount: (no) => client.get(`/api/agent/accounts/${no}`),
  getAgentCommission: (no) => client.get(`/api/agent/commissions/${no}`),

  // 代理清退（AgentExitController）：发起挂在代理资源下，推进 / 门禁挂在清退单资源下
  startAgentExit: (agentNo, reason) => client.post(`/api/agent/agents/${agentNo}/exit`, { reason }),
  getAgentExit: (no) => client.get(`/api/agent/exits/${no}`),
  agentExitGate: (no) => client.get(`/api/agent/exits/${no}/gate`),
  advanceAgentExit: (no) => client.post(`/api/agent/exits/${no}/advance`, {}),
  listAgentOpsAssessments: (agentNo) => client.get(`/api/agent/agents/${agentNo}/ops-assessments`),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  archiveAgent: (no) => client.post(`/api/agent/agents/${no}/archive`, {}),
  unarchiveAgent: (no) => client.post(`/api/agent/agents/${no}/unarchive`, {}),
};
