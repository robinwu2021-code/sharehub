// 覆盖范围：代理商主档、区域分配、业绩、资金账户、分润规则。
import type { PageQ, ArchiveQ, AssignmentRecordQ, AssignableAssetQ , ReportQ, ApplyQ } from "../query";
import type {
  PageResult, Agent, AgentAssignment, AgentPerformance, AgentAccount, AgentCommission,
  AgentAssignmentRecord, AssignableAsset, AssignAssetsPayload, ReclaimAssetsPayload,
  AgentApply, ApplyStatus, OperatorType, MyApplyView,
  AgentExit, AgentOpsAssessment, Checklist,
} from "../../types";

export interface AgentApi {
  listAgents(q?: ArchiveQ): Promise<PageResult<Agent>>;
  saveAgent(a: Partial<Agent> & { agentNo?: string }): Promise<Agent>;

  // === 代理商扩展 tab ===
  listAgentAssignments(q?: PageQ): Promise<PageResult<AgentAssignment>>;
  /** 代理绩效。period 复用报表域 ReportQ —— GMV 与站点坪效同一套周期口径。 */
  listAgentPerformance(q?: ReportQ): Promise<PageResult<AgentPerformance>>;
  listAgentAccounts(q?: PageQ): Promise<PageResult<AgentAccount>>;
  saveAgentAccount(x: Partial<AgentAccount> & { accountNo?: string }): Promise<AgentAccount>;

  // === 入驻申请（ADR-030 §三）===
  /**
   * 待办队列与历史检索。`status` 留空 = 在途（SUBMITTED + REVIEWING）。
   *
   * ⚠️ `keyword` 只搜主体名与申请单号，**不搜手机号/邮箱** ——
   * 库里存的是掩码，按它搜等于前缀模糊匹配，会把不相干的人捞出来。
   */
  listAgentApplies(q?: ApplyQ): Promise<PageResult<AgentApply>>;
  /** 受理：SUBMITTED → REVIEWING。权限码 `agent:apply:approve`。 */
  acceptAgentApply(applyNo: string, operatorName?: string): Promise<AgentApply>;
  /**
   * 审核。通过时服务端在**一个事务**里派生主体 + 自然人 + 属主账号。
   *
   * 驳回必须带原因 —— 它会原样回显给申请人。
   */
  auditAgentApply(x: {
    applyNo: string; approve: boolean; rejectReason?: string;
    shareRate?: number; regionScope?: string; operatorName?: string;
  }): Promise<AgentApply>;
  /**
   * 运营代建：替线下签约的商家录入申请。
   *
   * 与商家自助注册**落同一张表**，只是 `source` 不同（由服务端按令牌判定，不从这里传）。
   * 权限码 `agent:apply:create`，与放行码 `agent:apply:approve` **分开** ——
   * 合成一个的话「能录入」就等于「能放行」，四眼原则以后没有落脚点。
   */
  createAgentApply(x: {
    phone: string; email: string; operatorName: string; operatorType: OperatorType;
    regionScope?: string; shareRate?: number; payload?: string;
  }): Promise<AgentApply>;

  // === 自助注册（公开页 /apply 用，**免鉴权**）===
  /**
   * 发码。生产只回 `{sent:true}`；dev-mode 回 `devCode` 便于联调。
   *
   * ⚠️ 服务端发码时会先把手机号规范化，**与验码用的是同一个函数** ——
   * 否则「收到码了但验不过」，且只在带空格/连字符/国际区号的输入上出现。
   */
  sendApplyOtp(phone: string): Promise<{ sent: boolean; devCode?: string }>;
  /** 自助提交。落的是 `source=SELF_SERVICE`（由路由决定，不从这里传）。 */
  selfServiceApply(x: {
    phone: string; otp: string; email: string;
    operatorName: string; operatorType: OperatorType;
    regionScope?: string; payload?: string;
  }): Promise<{ applyNo: string; status: ApplyStatus }>;
  /** 申请人查自己的进度与驳回原因（凭手机号 + OTP）。 */
  myApply(phone: string, otp: string): Promise<MyApplyView>;

  // === S1 设备/点位划拨（权限码 agent:scope:assign）===
  /** 可划拨资产池（机柜 + 站点），带当前归属。抽屉一次拉全量（size 传大值），不做无限滚动。 */
  listAssignableAssets(q?: AssignableAssetQ): Promise<PageResult<AssignableAsset>>;
  /** 划拨：把机柜/站点挂到目标代理名下。改的是资产的 `agentNo`，整批成功或整批拒绝。 */
  assignAgentAssets(x: AssignAssetsPayload): Promise<AgentAssignmentRecord[]>;
  /** 回收：资产收回平台直营（`agentNo` 置 null）。不传代理号——从资产当前归属反查。 */
  reclaimAgentAssets(x: ReclaimAssetsPayload): Promise<AgentAssignmentRecord[]>;
  /** 划拨/回收流水（审计）。 */
  listAgentAssignmentRecords(q?: AssignmentRecordQ): Promise<PageResult<AgentAssignmentRecord>>;

  // === 代理分润 ===
  listAgentCommissions(q?: PageQ): Promise<PageResult<AgentCommission>>;
  saveAgentCommission(x: Partial<AgentCommission> & { ruleNo?: string }): Promise<AgentCommission>;

  // === 单条读（详情抽屉 / 深链）===
  /** 代理账号详情（权限码 `agent:account:manage`）。 */
  getAgentAccount(accountNo: string): Promise<AgentAccount>;
  /** 分润规则详情（权限码 `agent:share:config`）。 */
  getAgentCommission(ruleNo: string): Promise<AgentCommission>;

  // === 代理清退（运营核心流程 F3）===
  /**
   * 发起清退：**发起即停用**（冻结提现、名下未完结工单改派平台），之后按
   * 收回资产 → 结清 → 关闭账号 逐步推进。原因必填；同一代理至多一张在途清退单。
   * 权限码 `agent:agent:update`。
   */
  startAgentExit(agentNo: string, reason: string): Promise<AgentExit>;
  getAgentExit(exitNo: string): Promise<AgentExit>;
  /** 当前这一步的门禁（逐项、带数量、带去处）。`allPassed` 以服务端为准。 */
  agentExitGate(exitNo: string): Promise<Checklist>;
  /** 门禁全过 → 推进一步；最后一步停用全部登录账号并归档代理（不可逆）。服务端会重算门禁。 */
  advanceAgentExit(exitNo: string): Promise<AgentExit>;

  // === 运维月度考核（F5，权限码 `agent:performance:read`）===
  /** 考核历史（按考核月倒序）：达成率（被接管算未达成）· 在线率 · 客诉 · 被接管数 → 下月运维分成系数。 */
  listAgentOpsAssessments(agentNo: string): Promise<AgentOpsAssessment[]>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  archiveAgent(agentNo: string): Promise<Agent>;
  unarchiveAgent(agentNo: string): Promise<Agent>;
}
