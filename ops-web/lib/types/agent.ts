// 覆盖范围：代理商域（agt · ADR-012）——代理主体、区域划分、业绩、
// 代理端账号、分润规则配置。

export interface Agent {
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
  cabinetCount: number;
  siteCount: number;
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
  dataScope: string;
  createdAt: string;
}

// —— 代理商分润配置（代理域 · P1）——
export interface AgentCommission {
  ruleNo: string;
  agentNo: string;
  agentName: string;
  dimension: "GMV" | "ORDER_COUNT";
  rate: number; // 0~1
  mode: "CHANNEL_SPLIT" | "LEDGER";
  effectiveAt: string;
  status: "ACTIVE" | "INACTIVE";
}
