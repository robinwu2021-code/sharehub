"use client";

import { Badge } from "@/components/ui/badge";
import { useI18n } from "@/lib/i18n";
import type { OrderStatus, WorkOrderStatus, CabinetStatus, OnlineStatus } from "@/lib/types";

type Tone = "default" | "success" | "warning" | "danger" | "muted";

// 只保留「枚举→色调」在代码；文案统一走 i18n（orderStatus.* / woStatus.* / cabStatus.* / online.* / woType.*）。
const ORDER_TONE: Record<OrderStatus, Tone> = {
  CREATED: "muted", DISPENSING: "warning", IN_USE: "success",
  RETURNED: "default", SETTLED: "success", CLOSED: "muted", EXCEPTION: "danger",
};
const WO_TONE: Record<WorkOrderStatus, Tone> = {
  CREATED: "warning", DISPATCHED: "default", ACCEPTED: "default",
  PROCESSING: "warning", DONE: "success", AUDITED: "success", CLOSED: "muted",
};
const CAB_TONE: Record<CabinetStatus, Tone> = {
  DEPLOYED: "success", FAULT: "danger", RETIRED: "muted",
};

export const OrderStatusBadge = ({ s }: { s: OrderStatus }) => {
  const { t } = useI18n();
  return <Badge tone={ORDER_TONE[s]}>{t(`orderStatus.${s}`)}</Badge>;
};
export const WoStatusBadge = ({ s }: { s: WorkOrderStatus }) => {
  const { t } = useI18n();
  return <Badge tone={WO_TONE[s]}>{t(`woStatus.${s}`)}</Badge>;
};
export const CabinetStatusBadge = ({ s }: { s: CabinetStatus }) => {
  const { t } = useI18n();
  return <Badge tone={CAB_TONE[s]}>{t(`cabStatus.${s}`)}</Badge>;
};
export const OnlineBadge = ({ s }: { s: OnlineStatus }) => {
  const { t } = useI18n();
  return s === "ONLINE" ? <Badge tone="success">{t("online.ONLINE")}</Badge> : <Badge tone="muted">{t("online.OFFLINE")}</Badge>;
};

/** 工单类型标签（hook）：页面用 `const woTypeLabel = useWoTypeLabel(); woTypeLabel(type)`。 */
export function useWoTypeLabel() {
  const { t } = useI18n();
  return (type: string) => t(`woType.${type}`);
}

// 兼容旧引用（work-orders 页面仍在用；P2 换 useWoTypeLabel）。中文兜底。
export const WO_TYPE_LABEL: Record<string, string> = {
  FAULT: "故障维修", REFILL: "缺货补货", INSPECT: "巡检", INSTALL: "安装",
  REMOVE: "撤机", COMPLAINT: "投诉", CLEAN: "清洁",
};
