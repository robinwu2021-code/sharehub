// 设备域「枚举 → 文案 + 色调」映射（批次 5b）。台账页、详情页、调拨抽屉共用一份：
// 同一个枚举在两个地方配出不同颜色 / 叫法，是 StatusMap 收敛前反复出现的问题。
import type { StatusMap } from "@/components/ui/status-badge";
import type {
  AssetDiffKind, AssetDiffStatus, CabinetStatus, InvTransferStatus, PowerbankHealth, PowerbankStatus,
  ProtectionAction, ProtectionHolderType, QcStatus, TransferEndpointType, TransferItemType, TrialRentStatus,
} from "@/lib/types";

/** 机柜状态。文案与 i18n `cabStatus.*` 一致；色调与 components/status.tsx 的 CAB_TONE 一致。 */
export const CAB_STATUS: StatusMap<CabinetStatus> = {
  IN_STOCK: { label: "在库", tone: "info" },
  IN_TRANSIT: { label: "运输中", tone: "warning" },
  DEPLOYED: { label: "在用", tone: "success" },
  FAULT: { label: "故障", tone: "danger" },
  RETIRED: { label: "报废", tone: "muted" },
};

export const PB_STATUS: StatusMap<PowerbankStatus> = {
  IN_STOCK: { label: "在库", tone: "info" },
  IN_CABINET: { label: "在仓", tone: "success" },
  RENTED: { label: "借出中", tone: "warning" },
  FAULT: { label: "故障", tone: "danger" },
  // 丢失是**半终态**：追回来还能回仓（后端 RECOVER 边），所以不是 muted 而是要能看见
  LOST: { label: "丢失待追偿", tone: "danger" },
  SOLD: { label: "已买断", tone: "muted" },
  SCRAP: { label: "已报废", tone: "muted" },
};

/** 充电宝健康。AGED = 循环次数超限、已停止借出、待回收报废（后端每日定时标记）。 */
export const PB_HEALTH: StatusMap<PowerbankHealth> = {
  OK: { label: "正常", tone: "success" },
  FAULT: { label: "故障", tone: "danger" },
  AGED: { label: "老化待报废", tone: "warning" },
};

export const TRIAL_STATUS: StatusMap<TrialRentStatus> = {
  EJECTING: { label: "弹出中", tone: "warning" },
  WAIT_RETURN: { label: "等待归还", tone: "warning" },
  PASSED: { label: "通过", tone: "success" },
  FAILED: { label: "失败", tone: "danger" },
  EXPIRED: { label: "超时", tone: "muted" },
};

export const PROTECTION_ACTION: StatusMap<ProtectionAction> = {
  STOP_RENT: { label: "整柜停借", tone: "danger" },
  SLOT_DISABLE: { label: "仓位禁用", tone: "warning" },
  SLOT_LOCK: { label: "仓位锁定", tone: "danger" },
  DERATE: { label: "降功率", tone: "info" },
};

/** 保护动作的一句话说明（挂保护的下拉里给人看：影响面从大到小）。 */
export const PROTECTION_HINT: Record<ProtectionAction, string> = {
  STOP_RENT: "整柜不再借出，仍可归还",
  SLOT_DISABLE: "该仓不借不还",
  SLOT_LOCK: "该仓锁死、宝不弹出（电池异常时的安全隔离）",
  DERATE: "整柜降功率充电（过热时）",
};

export const HOLDER_TYPE: StatusMap<ProtectionHolderType> = {
  SIGNAL: { label: "设备信号", tone: "outline" },
  ALARM: { label: "业务告警", tone: "outline" },
  MANUAL: { label: "人工", tone: "default" },
};

export const QC_STATUS: StatusMap<QcStatus> = {
  PENDING: { label: "待质检", tone: "warning" },
  PASSED: { label: "质检通过", tone: "success" },
  FAILED: { label: "质检不通过", tone: "danger" },
};

export const TRANSFER_STATUS: StatusMap<InvTransferStatus> = {
  DRAFT: { label: "草稿", tone: "muted" },
  IN_TRANSIT: { label: "在途", tone: "warning" },
  DONE: { label: "已完成", tone: "success" },
};

export const DIFF_STATUS: StatusMap<AssetDiffStatus> = {
  OPEN: { label: "待查清", tone: "warning" },
  RESOLVED: { label: "已处理", tone: "success" },
};

export const DIFF_KIND: StatusMap<AssetDiffKind> = {
  MISSING: { label: "缺件", tone: "danger" },
  EXTRA: { label: "多件", tone: "warning" },
  COUNT_MISMATCH: { label: "清点数不符", tone: "warning" },
};

export const ENDPOINT_TYPE_LABEL: Record<TransferEndpointType, string> = {
  WAREHOUSE: "仓库", SITE: "站点", LOCATION: "点位",
};

export const ITEM_TYPE_LABEL: Record<TransferItemType, string> = {
  CABINET: "机柜", POWERBANK: "充电宝",
};

/**
 * 从某个时间点到现在过了几天（至少 1 天）。
 *
 * <p>疑似丢失要回答的是「拖了多久还没人核实」，时间戳回答不了这个 ——
 * 看到「2026-09-18 03:30」还得自己心算。
 */
export function daysSince(at: string): number {
  const t = new Date(at.replace(" ", "T")).getTime();
  if (Number.isNaN(t)) return 1;
  return Math.max(1, Math.floor((Date.now() - t) / 86400000) + 1);
}
