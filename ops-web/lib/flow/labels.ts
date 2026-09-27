// 流程与工单的展示映射。只放**平台自己的概念**（待办类型、状态大类的色调、优先级序数的色调）；
// 业务词（工单类型名、状态名、办理人类型、来源）一律用服务给的 name，不在这里内置（21/06 §七）。
import type { BadgeTone } from "@/components/ui/badge";
import type { StatusMap } from "@/components/ui/status-badge";
import type { ApprovalStatus, StatusCategory, TodoOrigin } from "./types";

/** 待办类型徽标。可抢单单独一档：它和「已派给我的」要一眼分开 */
export const TODO_KIND: StatusMap<TodoOrigin | "POOL"> = {
  PROCESS: { label: "审批", tone: "info" },
  TICKET: { label: "工单", tone: "default" },
  POOL: { label: "可抢", tone: "outline" },
};

/** 状态徽标的色调按跨类型可比的大类取；文案用服务给的 status.name */
export const CATEGORY_TONE: Record<StatusCategory, BadgeTone> = {
  NEW: "warning",
  IN_PROGRESS: "success",
  WAITING: "muted",
  DONE: "outline",
  CANCELLED: "muted",
};

/** 优先级色调按 ordinal（0 最急）映射，不按代号写死 —— 租户可以自定义优先级选项集（21/06 §七） */
export function priorityTone(ordinal: number): BadgeTone {
  return ordinal <= 0 ? "danger" : ordinal === 1 ? "warning" : "muted";
}

/** 审批单状态（平台概念，非业务词） */
export const APPROVAL_STATUS: StatusMap<ApprovalStatus> = {
  PENDING: { label: "待审", tone: "warning" },
  APPROVED: { label: "已通过", tone: "success" },
  REJECTED: { label: "已驳回", tone: "danger" },
  WITHDRAWN: { label: "已撤回", tone: "muted" },
};
