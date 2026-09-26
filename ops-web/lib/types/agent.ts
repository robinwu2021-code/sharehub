import type { Archivable } from "./common";

import type { DataScope } from "./org";

// 覆盖范围：代理商域（agt · ADR-012）——代理主体、区域划分、业绩、
// 代理端账号、分润规则配置。

/**
 * 登记类型（ADR-027 §一）。
 *
 * 两者不是叫法之别：代理商出资 + 运维，城市合伙人还包拓展与效果管理，**拿的钱性质不同**。
 * 类型只定「他大体是哪种人」；「他在**这个站点**做了什么」由将来的「伙伴 × 站点 × 责任」
 * 表达（A2）—— 两者都要，否则「这个人在 A 站是介绍人、在 B 站是代理商」无处安放。
 */
export type AgentType = "AGENT" | "CITY_PARTNER";

/** 与后端 `AgentStatus` 枚举同名同值。**具名不是风格** —— 两端同名词表比对只认
 *  具名 `export type`，内联在 interface 里的联合它一个都发现不了。 */
export type AgentStatus = "ENABLED" | "SUSPENDED";
export interface Agent extends Archivable {
  agentNo: string;
  name: string;
  contact: string;
  regionScope: string;
  /** 登记类型；存量与缺省为 `AGENT`。 */
  agentType: AgentType;
  shareRate: number; // 默认分润比例 0..1
  cabinetCount: number;
  status: AgentStatus;
}

// —— 入驻申请（ADR-030 §三）——

/**
 * 申请来源。
 *
 * **`SELF_SERVICE` 与 `OPS_CREATED` 走的是同一张表、同一个状态机、同一套必填校验** ——
 * 用户 2026-09-23 定的「条件相同」就是这个意思：不按来源分叉。
 * 差别只在谁按下提交、以及自助那一侧要验手机号 OTP。
 *
 * ⚠️ **前端不传这个字段**：服务端按有无 STAFF 令牌判定。
 * 让客户端传的话，自助申请可以自称代建，绕开 OTP 与限流。
 */
export type ApplySource = "SELF_SERVICE" | "OPS_CREATED";

export type ApplyStatus = "DRAFT" | "SUBMITTED" | "REVIEWING" | "APPROVED" | "REJECTED";

/** 运营主体类型。`CITY_PARTNER` 是 ADR-027 加的，与代理商同层不同责任。 */
export type OperatorType = "AGENT" | "CITY_PARTNER";

export interface AgentApply {
  applyNo: string;
  source: ApplySource;
  status: ApplyStatus;
  operatorName: string;
  operatorType: OperatorType;
  /**
   * 手机号与邮箱一律**只给掩码**。
   *
   * 明文在服务端（可逆加密），登录键是 HMAC —— 掩码不可逆也会碰撞
   * （`13800138000` 与 `13811138000` 掩码相同），拿它做任何等值判断都是错的。
   */
  phoneMask: string;
  emailMask: string;
  regionScope: string | null;
  shareRate: number | null;
  /** 资质材料。敏感件只存 pii 引用，不落明文（PDPL）。 */
  payload: string | null;
  /** 命中已有自然人时回填 —— **手机号已存在不是重复注册，是多主体申请**。 */
  principalNo: string | null;
  /** 驳回原因。**原样回显给申请人**，不是只给运营看。 */
  rejectReason: string | null;
  submittedBy: string | null;
  submittedAt: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  /** 审核通过后回写，申请 ↔ 主体双向可查。 */
  operatorNo: string | null;
  /** 这个手机号已经有主体了（多主体申请）——审核台要显眼地标出来。 */
  phoneAlreadyKnown: boolean;
  /** 已有自然人的邮箱与本次申请不一致时给出，**由审核人裁决，不静默覆盖**。 */
  knownEmailMask: string | null;
}

/**
 * 申请人视角：**只看得到掩码与状态**，看不到内部字段。
 *
 * 公开页面向的是陌生人，任何多给的信息都是可枚举面 ——
 * 比如「这个手机号已经有主体了」就不能说，那等于给了一个查号工具。
 */
export interface MyApplyView {
  applyNo: string;
  status: ApplyStatus;
  operatorName: string;
  phoneMask: string;
  emailMask: string;
  /** 驳回原因，**原样回显** —— 不告诉申请人错在哪，他只能反复猜着重提。 */
  rejectReason: string | null;
  submittedAt: string | null;
}

// —— 代理商 · 待建功能补全（agt 域）——
export interface AgentAssignment {
  agentNo: string;
  agentName: string;
  region: string;
  /** 设备数/点位数一律由 `cabinets.agentNo` / `sites.agentNo` **实时反算**，不另存一份计数——
   * 否则划拨完汇总不动，页面看起来像没生效。 */
  cabinetCount: number;
  siteCount: number;
}

// —— 设备/点位划拨（S1）：资产归属的唯一写入口 ——
/** 可划拨资产的两类主体：机柜（`cabinets.cabinetNo`）与站点（`sites.siteNo`）。 */
export type AssetType = "CABINET" | "SITE";
/** 划拨 = 挂到某代理名下；回收 = 收回平台直营（`agentNo` 置 null）。 */
export type AssignAction = "ASSIGN" | "RECLAIM";

/**
 * 划拨/回收流水（审计）。RECLAIM 记的是**收回前**的归属方——
 * 「从谁手里收回来的」才是审计要查的信息。
 */
export interface AgentAssignmentRecord {
  assignmentNo: string;
  agentNo: string;
  agentName: string;
  assetType: AssetType;
  assetNo: string;
  action: AssignAction;
  operatorName: string;
  createdAt: string;
}

/** 划拨抽屉的候选资产。带当前归属，选项上标注出来，避免把别人的柜子误划走。 */
export interface AssignableAsset {
  assetType: AssetType;
  assetNo: string;
  /** 展示名：机柜取点位名、站点取站点名 */
  name: string;
  currentAgentNo: string | null;
  currentAgentName: string | null;
}

/** 划拨入参。两类资产可一次提交（前端一个抽屉，后端一个事务）。 */
export interface AssignAssetsPayload {
  agentNo: string;
  cabinetNos: string[];
  siteNos: string[];
  /** 操作人（后端以会话为准，前端透传便于 mock 留痕） */
  operatorName?: string;
}
/** 回收入参：不需要代理号——从资产当前归属反查，杜绝「传错代理把别人的柜子收了」。 */
export interface ReclaimAssetsPayload {
  cabinetNos: string[];
  siteNos: string[];
  operatorName?: string;
}
export interface AgentPerformance {
  agentNo: string;
  agentName: string;
  gmv: number;
  cabinetCount: number;
  onlineRate: number; // 0..1
  rank: number;
  currency: string;
}
export interface AgentAccount {
  accountNo: string;
  agentNo: string;
  agentName: string;
  /** 登录名。运营找「是哪个账号」靠它，手机号可能多账号共用。 */
  username: string | null;
  loginPhone: string;
  status: "ACTIVE" | "DISABLED";
  /**
   * 授权范围。**真后端目前恒为 null** —— `agt_account` 有意没有这一列，落点本该是
   * `iam_data_scope(subject_type='AGENT_ACCOUNT')`，而 `AGENT_ACCOUNT` 还不在后端的
   * `DataScopeSubject` 词表里（只有 ROLE / EMPLOYEE）。整块没有实现，不是漏查。
   * 「代理账号能不能看得比它所属代理更窄」是待裁决项，见 待裁决清单-9-25。
   */
  dataScope: DataScope | null; // 台账 T5：原为 string，与 RoleRow.dataScope 统一
  createdAt: string;
}

// —— 代理商分润配置（代理域 · P1）——
export interface AgentCommission {
  ruleNo: string;
  agentNo: string;
  agentName: string;
  basis: "GMV" | "ORDER_COUNT";
  rate: number; // 0~1
  /** 固定额佣金（与 rate 二选一；两者都配时以固定额为准）。 */
  fixedAmount: number | null;
  /** 币种。多市场下只给金额不给币种，数字没有意义。 */
  currency: string | null;
  mode: "CHANNEL_SPLIT" | "LEDGER";
  effectiveAt: string;
  status: "ACTIVE" | "INACTIVE";
}

// —— 代理清退（运营核心流程 F3 · 后端 AgentExitController）——

/**
 * 清退单状态。与后端 `AgentExitStatus` 枚举同名同值（两端词表比对只认具名 `export type`）。
 * 严格按序：收回资产 → 结清 → 关闭账号 → 已清退。**没有回退边**：资产收回了再「退回收回中」没有业务含义。
 */
export type AgentExitStatus = "RECLAIMING" | "SETTLING" | "CLOSING" | "CLOSED";
export type AgentExitAction = "reclaimed" | "settled" | "close";

/**
 * 清退迁移表（SSOT）。与后端 `AgentExitStateMachine.TRANSITIONS` 边对边一致
 * （`StateMachineEdgeAcrossEndsTest` 两端比对）。每一步都要**当前步门禁全过**才放行，
 * 门禁由服务端算（`GET /api/agent/exits/{exitNo}/gate`），这里只管「这一步之后是哪一步」。
 */
export const AGENT_EXIT_TRANSITIONS: Record<AgentExitAction, { from: AgentExitStatus[]; to: AgentExitStatus; label: string }> = {
  reclaimed: { from: ["RECLAIMING"], to: "SETTLING", label: "资产已收回，进入结清" },
  settled: { from: ["SETTLING"], to: "CLOSING", label: "已结清，进入关闭" },
  close: { from: ["CLOSING"], to: "CLOSED", label: "关闭账号并归档" },
};
/** 当前状态下「推进」对应的动作；终态没有。 */
export const agentExitActionOf = (s: AgentExitStatus): AgentExitAction | null =>
  (Object.keys(AGENT_EXIT_TRANSITIONS) as AgentExitAction[]).find((a) => AGENT_EXIT_TRANSITIONS[a].from.includes(s)) ?? null;

/** 后端 `AgentExitService.AgentExit` 原样形状。 */
export interface AgentExit {
  exitNo: string;
  agentNo: string;
  status: AgentExitStatus;
  reason: string | null;
  startedBy: string | null;
  startedAt: string | null;
  reclaimedAt: string | null;
  settledAt: string | null;
  closedAt: string | null;
  closedBy: string | null;
}

/**
 * 运维月度考核（后端实体 `AgtOpsAssessment`，V110）。
 *
 * 达成率 = 考核月内该代理完结且未超时的工单 ÷（完结的 + **被平台接管的**）——
 * 被接管算没达成，否则「超时不管、等平台接走」反而不影响考核。
 * 系数用于 `applyPeriod`（下一个月）的运维分成，不逐单扣分润。
 * 比率类字段在「无从考核」时为 null（没有工单 / 没有在网设备），系数此时为 1。
 */
export interface AgentOpsAssessment {
  id: number;
  agentNo: string;
  /** 考核月 `YYYY-MM`。 */
  period: string;
  /** 系数生效月（考核月 + 1）。 */
  applyPeriod: string;
  woTotal: number;
  woInSla: number;
  slaRate: number | null;
  onlineRate: number | null;
  complaints: number;
  takenOver: number;
  coefficient: number;
  computedAt: string | null;
  createdAt: string | null;
}
