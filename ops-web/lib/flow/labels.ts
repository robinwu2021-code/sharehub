// 流程与工单的展示映射（色调在这里，文案走 i18n flow.common.*）。只放**平台自己的概念**（待办类型、状态大类的色调、优先级序数的色调）；
// 业务词（工单类型名、状态名、办理人类型、来源）一律用服务给的 name，不在这里内置（21/06 §七）。
import type { BadgeTone } from "@/components/ui/badge";
import type { StatusMap } from "@/components/ui/status-badge";
import type { ApprovalStatus, StatusCategory, TodoOrigin } from "./types";
import type { CommonKey } from "./i18n";

type Tc = (k: CommonKey) => string;
const withLabels = <K extends string>(tone: Record<K, BadgeTone>, label: (k: K) => string): StatusMap<K> =>
  Object.fromEntries((Object.keys(tone) as K[]).map((k) => [k, { label: label(k), tone: tone[k] }])) as StatusMap<K>;

/** 待办类型徽标。可抢单单独一档：它和「已派给我的」要一眼分开 */
const TODO_KIND_TONE: Record<TodoOrigin | "POOL", BadgeTone> = { PROCESS: "info", TICKET: "default", POOL: "outline" };
export const todoKindMap = (tc: Tc) => withLabels(TODO_KIND_TONE, (k) => tc(`todoKind.${k}`));

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
const APPROVAL_TONE: Record<ApprovalStatus, BadgeTone> = { PENDING: "warning", APPROVED: "success", REJECTED: "danger", WITHDRAWN: "muted" };
export const approvalStatusMap = (tc: Tc) => withLabels(APPROVAL_TONE, (k) => tc(`approvalStatus.${k}`));
