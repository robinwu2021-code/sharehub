// 业务告警的「枚举 → 文案 + 色调」映射（告警记录页、告警代码页、详情抽屉、看板待办共用一份）。
//
// 放 components/alarm/ 而不是各页各写：同一个「处置方式」在列表列、筛选下拉、详情头、
// 预览确认里出现四次，四处各写一份迟早出现「列表写转客服、详情写客服单」。
// 键序 = 筛选下拉的顺序（statusOptions 按键序派生）。
import type { StatusMap } from "@/components/ui/status-badge";
import type {
  AlarmLevel, AlarmStatus, AlarmDomain, AlarmCause, AlarmDisposition, AlarmSubjectType,
  ImpactScope, ImpactPeriod, AlarmCloseReason, AlarmLogEvent, AlarmTodoStatus, WorkOrderPriority,
  AlarmEvalType, AlarmSource,
} from "@/lib/types";
import type { RefKind } from "@/components/ref-link";

/**
 * 告警等级：文案 + 色调 + **形状阶梯**（规范 §11.4）。
 * warning/danger 两档对红绿色盲几乎同色，故文案里带一条与颜色无关的阶梯：▫ < ▲ < ▲▲。
 */
export const ALARM_LEVEL: StatusMap<AlarmLevel> = {
  INFO: { label: "▫ 提示", tone: "muted" },
  WARN: { label: "▲ 警告", tone: "warning" },
  CRITICAL: { label: "▲▲ 严重", tone: "danger" },
};

export const ALARM_STATUS: StatusMap<AlarmStatus> = {
  OPEN: { label: "待处理", tone: "warning" },
  ACKED: { label: "已受理", tone: "default" },
  CLOSED: { label: "已关闭", tone: "muted" },
};

/**
 * 处置优先级（工单词表）。**与等级不是一套**：等级说事情本身多严重，优先级说现在该多快去办。
 * 用文字前缀区分（P0…P3），不和等级的 ▲ 阶梯混用 —— 两列并排时一眼分得开。
 */
export const ALARM_PRIORITY: StatusMap<WorkOrderPriority> = {
  URGENT: { label: "P0 紧急", tone: "danger" },
  HIGH: { label: "P1 高", tone: "warning" },
  MEDIUM: { label: "P2 中", tone: "default" },
  LOW: { label: "P3 低", tone: "muted" },
};

/** 业务域。短名来自方案 §8.1 的分段文案。 */
export const ALARM_DOMAIN: StatusMap<AlarmDomain> = {
  AVAILABILITY: { label: "可借", tone: "outline" },
  RETURNABILITY: { label: "可还", tone: "outline" },
  TRANSACTION: { label: "交易", tone: "outline" },
  SAFETY: { label: "安全", tone: "outline" },
  ASSET: { label: "资产", tone: "outline" },
  REVENUE: { label: "经营", tone: "outline" },
  SERVICE: { label: "履约", tone: "outline" },
  PARTNER: { label: "合作", tone: "outline" },
  FUND: { label: "资金", tone: "outline" },
};
export const ALARM_DOMAINS = Object.keys(ALARM_DOMAIN) as AlarmDomain[];

export const ALARM_CAUSE: StatusMap<AlarmCause> = {
  OFFLINE: { label: "离线", tone: "outline" },
  UNSTABLE: { label: "频繁掉线", tone: "outline" },
  NO_STOCK: { label: "无宝", tone: "outline" },
  FULL: { label: "满柜", tone: "outline" },
  FAULT: { label: "故障", tone: "outline" },
  MIXED: { label: "多因", tone: "outline" },
  LOW_BATTERY: { label: "低电", tone: "outline" },
  OVERHEAT: { label: "过热", tone: "outline" },
  HAZARD: { label: "隐患", tone: "outline" },
  AGED: { label: "老化", tone: "outline" },
  MISSING: { label: "失联", tone: "outline" },
  SN_SEEN: { label: "异地出现", tone: "outline" },
  NO_CONTRACT: { label: "无合同", tone: "outline" },
  EXPIRING: { label: "将到期", tone: "outline" },
  LOW_YIELD: { label: "低效", tone: "outline" },
  SLA_BELOW: { label: "不达标", tone: "outline" },
  CANCEL_FAILED: { label: "撤销失败", tone: "outline" },
};

export const ALARM_DISPOSITION: StatusMap<AlarmDisposition> = {
  WORK_ORDER: { label: "开工单", tone: "default" },
  CS_CASE: { label: "转客服", tone: "info" },
  TODO: { label: "挂待办", tone: "info" },
  AUTO_FIX: { label: "系统自愈", tone: "success" },
  NOTIFY: { label: "仅通知", tone: "muted" },
};

export const ALARM_SUBJECT: Record<AlarmSubjectType, string> = {
  SITE: "站点", CABINET: "机柜", SLOT: "仓位", POWERBANK: "充电宝", ORDER: "订单", USER: "用户",
  AGENT: "代理", PAYEE: "分成方", CONTRACT: "合同", PAYMENT: "支付", WORK_ORDER: "工单",
};

/** 主体类型 → RefLink 种类；没有详情页的主体（充电宝 / 用户 / 支付…）不给链接。 */
export const SUBJECT_REF: Partial<Record<AlarmSubjectType, RefKind>> = {
  SITE: "site", CABINET: "cabinet", SLOT: "cabinet", ORDER: "order", CONTRACT: "contract",
  AGENT: "agent", WORK_ORDER: "wo",
};
/** 仓位主体的编号是 `柜号#仓位`，链接到柜。 */
export const subjectRefNo = (type: AlarmSubjectType | null, no: string | null) =>
  type === "SLOT" && no ? no.split("#")[0] : no;

export const IMPACT_SCOPE: Record<ImpactScope, string> = {
  SITE: "整站", CABINET: "单柜", SLOT: "单仓", ORDER: "单笔订单", ENTITY: "单个对象",
};
export const IMPACT_PERIOD: Record<ImpactPeriod, string> = { PEAK: "高峰", OPEN: "营业中", CLOSED: "非营业" };

/** 关闭原因（含两档系统原因：只由引擎写，人工不可选 —— 见 ALARM_CLOSE_REASONS）。 */
export const ALARM_CLOSE_REASON: StatusMap<AlarmCloseReason> = {
  RESOLVED: { label: "已解决", tone: "success" },
  FALSE_ALARM: { label: "误报", tone: "warning" },
  SELF_HEALED: { label: "已自愈", tone: "muted" },
  AUTO_FIXED: { label: "系统自愈", tone: "muted" },
  SUPERSEDED: { label: "被上层取代", tone: "muted" },
};

export const ALARM_LOG_EVENT: StatusMap<AlarmLogEvent> = {
  OPEN: { label: "成立", tone: "warning" },
  IMPACT_UP: { label: "影响升级", tone: "danger" },
  SUPERSEDE: { label: "被取代", tone: "muted" },
  FIX_TRY: { label: "尝试自愈", tone: "info" },
  FIX_FAIL: { label: "自愈失败", tone: "danger" },
  DISPOSE: { label: "处置", tone: "default" },
  RECOVER: { label: "恢复", tone: "success" },
  RELAPSE: { label: "复发", tone: "warning" },
  CLOSE: { label: "关闭", tone: "muted" },
};

export const ALARM_TODO_STATUS: StatusMap<AlarmTodoStatus> = {
  OPEN: { label: "待办", tone: "warning" },
  DONE: { label: "已办结", tone: "success" },
  CANCELLED: { label: "已撤销", tone: "muted" },
};

export const ALARM_EVAL: Record<AlarmEvalType, string> = {
  EVENT: "事件即成立", STATE: "持续", COUNT: "窗口计次", METRIC: "周期指标",
};

export const ALARM_SOURCE: StatusMap<AlarmSource> = {
  EVAL: { label: "业务判定", tone: "outline" },
  DEVICE: { label: "设备", tone: "outline" },
  OTA: { label: "OTA", tone: "outline" },
  RENT: { label: "租借", tone: "outline" },
};

/** 岗位码 → 人话（待办的承接岗位）。与 lib/auth 的 Role 同值。 */
export const ROLE_LABEL: Record<string, string> = {
  ADMIN: "管理员", OPS: "运维", CS: "客服", FINANCE: "财务", BD: "BD", VIEWER: "只读", AGENT: "代理",
};
export const roleLabel = (r: string | null | undefined) => (r ? ROLE_LABEL[r] ?? r : "-");

/** 持续时长：成立 → 恢复 / 关闭 / 现在。短写（「3 小时 12 分」），列里放得下。 */
export function durationText(fromIso: string | null | undefined, toIso?: string | null): string {
  if (!fromIso) return "-";
  const from = new Date(fromIso.replace(" ", "T")).getTime();
  const to = toIso ? new Date(toIso.replace(" ", "T")).getTime() : Date.now();
  if (Number.isNaN(from) || Number.isNaN(to) || to < from) return "-";
  const m = Math.floor((to - from) / 60000);
  if (m < 60) return `${m} 分`;
  const h = Math.floor(m / 60);
  if (h < 48) return `${h} 小时${m % 60 ? ` ${m % 60} 分` : ""}`;
  return `${Math.floor(h / 24)} 天`;
}
