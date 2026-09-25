// 商机（BD CRM）的展示映射：列表、详情抽屉、漏斗共用一份，改文案只改这里。
import type { LeadStage, LeadFollowChannel, ContractShareMode } from "@/lib/types";
import type { StatusMap } from "@/components/ui/status-badge";
import type { Step } from "@/components/ui/status-stepper";

export const LEAD_STAGE: StatusMap<LeadStage> = {
  NEW: { label: "新线索", tone: "muted" },
  CONTACTED: { label: "已接触", tone: "outline" },
  NEGOTIATING: { label: "洽谈中", tone: "warning" },
  SIGNED: { label: "已签约", tone: "success" },
  LOST: { label: "已丢单", tone: "danger" },
};

/** 主线四步；LOST 是分支终态（从哪一步岔出去由 lostFrom 推断）。 */
export const LEAD_STEPS: Step[] = [
  { key: "NEW", label: "新线索" },
  { key: "CONTACTED", label: "已接触" },
  { key: "NEGOTIATING", label: "洽谈中" },
  { key: "SIGNED", label: "已签约" },
];

export const FOLLOW_CHANNEL_LABEL: Record<LeadFollowChannel, string> = {
  CALL: "电话", VISIT: "拜访", WHATSAPP: "WhatsApp", EMAIL: "邮件", OTHER: "其他",
};

export const SHARE_MODE_LABEL: Record<ContractShareMode, string> = {
  SHARE: "纯分成", ENTRY_FEE: "进场费", GUARANTEE: "保底 + 分成", FREE: "免费",
};

/** 「今天该打谁」：下次跟进日相对今天的状态。空 = 未约。 */
export type FollowDue = "OVERDUE" | "TODAY" | "LATER" | "NONE";
export const FOLLOW_DUE: StatusMap<FollowDue> = {
  OVERDUE: { label: "已逾期", tone: "danger" },
  TODAY: { label: "今天", tone: "warning" },
  LATER: { label: "已约", tone: "outline" },
  NONE: { label: "未约", tone: "muted" },
};
export function followDue(nextFollowAt: string | null | undefined, today = new Date().toISOString().slice(0, 10)): FollowDue {
  const d = (nextFollowAt ?? "").slice(0, 10);
  if (!d) return "NONE";
  return d < today ? "OVERDUE" : d === today ? "TODAY" : "LATER";
}
