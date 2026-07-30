import type { Archivable } from "./common";

import type { DataScope } from "./org";

// 覆盖范围：代理商域（agt · ADR-012）——代理主体、区域划分、业绩、
// 代理端账号、分润规则配置。

export interface Agent extends Archivable {
  agentNo: string;
  name: string;
  contact: string;
  regionScope: string;
  shareRate: number; // 默认分润比例 0..1
  cabinetCount: number;
  status: "ENABLED" | "SUSPENDED";
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
  loginPhone: string;
  status: "ACTIVE" | "DISABLED";
  dataScope: DataScope; // 台账 T5：原为 string，与 RoleRow.dataScope 统一
  createdAt: string;
}

// —— 代理商分润配置（代理域 · P1）——
export interface AgentCommission {
  ruleNo: string;
  agentNo: string;
  agentName: string;
  basis: "GMV" | "ORDER_COUNT";
  rate: number; // 0~1
  mode: "CHANNEL_SPLIT" | "LEDGER";
  effectiveAt: string;
  status: "ACTIVE" | "INACTIVE";
}
