// 覆盖范围：工单域（ops）——工单主体、SLA 规则、巡检计划。

export type WorkOrderType =
  | "FAULT" | "REFILL" | "INSPECT" | "INSTALL" | "REMOVE" | "COMPLAINT" | "CLEAN";
export type WorkOrderStatus =
  | "CREATED" | "DISPATCHED" | "ACCEPTED" | "PROCESSING" | "DONE" | "AUDITED" | "CLOSED";
export interface WorkOrder {
  woNo: string;
  type: WorkOrderType;
  source: "ALERT" | "USER" | "VENUE" | "MANUAL";
  priority: "LOW" | "MEDIUM" | "HIGH";
  cabinetNo: string | null;
  locationName?: string | null;
  status: WorkOrderStatus;
  assigneeName: string | null;
  slaDueAt: string | null;
  description: string;
  createdAt: string;
}

// —— 工单 · 待建功能补全（ops 域）——
export interface SlaRule {
  slaNo: string;
  woType: string;
  responseMins: number;
  resolveMins: number;
  escalateTo: string;
  active: boolean;
}
export interface InspectionPlan {
  planNo: string;
  route: string;
  frequency: string;
  nextAt: string;
  assignee: string;
  active: boolean;
}
