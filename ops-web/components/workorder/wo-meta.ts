// 工单页的展示词表（业务语义：哪个值叫什么、什么色）。页面与 components/workorder/* 共用一份，
// 同一个枚举在列表、详情、看板上配出同一个颜色和名字。
import type { StatusMap } from "@/components/ui/status-badge";
import type { Step } from "@/components/ui/status-stepper";
import type {
  WorkOrder, WorkOrderPriority, WoAuditResult, WoFaultReason, WoReviewStatus, WoCostBearer, WoCloseReason,
  AlarmLevel,
} from "@/lib/types";

/**
 * 优先级：文案 + 色调 + **形状阶梯**（规范 §11.4）。
 * 紧急与高同为 danger 色 —— 红绿色盲（男性约 8%）看不出「紧急比高更急」，
 * 而优先级直接决定值班响应顺序，故在文案里带一条与颜色无关的阶梯：▫ < ▪ < ▲ < ▲▲。
 * 键序 = 下拉选项顺序（低→紧急）。
 */
export const PRIO: StatusMap<WorkOrderPriority> = {
  LOW: { label: "▫ 低", tone: "muted" },
  MEDIUM: { label: "▪ 中", tone: "warning" },
  HIGH: { label: "▲ 高", tone: "danger" },
  URGENT: { label: "▲▲ 紧急", tone: "danger" },
};

export const SOURCE_LABEL: Record<WorkOrder["source"], string> = {
  ALERT: "告警转入", USER: "投诉转入", VENUE: "场地方报障", MANUAL: "手工开单", PLAN: "巡检计划", INSPECTION: "巡检派生",
};

export const AUDIT_LABEL: Record<WoAuditResult, string> = {
  PASS: "验收合格", PASS_WITH_ISSUE: "有条件通过（有遗留）", FAIL: "验收不合格（退回返工）",
};

/** 故障原因（字典 wo_fault_reason）。键序 = 下拉顺序。 */
export const FAULT_REASON_LABEL: Record<WoFaultReason, string> = {
  NETWORK: "网络", POWER: "供电", SLOT_MECH: "仓位机械", LOCK: "锁", BATTERY: "电池",
  SCREEN: "屏幕", DAMAGE: "人为损坏", OTHER: "其他",
};
export const FAULT_REASON_OPTIONS = Object.entries(FAULT_REASON_LABEL).map(([value, label]) => ({ value, label }));

/** 完工复核结果：✓ / ✗ 用形状而不只靠颜色。 */
export const REVIEW: StatusMap<WoReviewStatus> = {
  PASSED: { label: "✓ 复核通过", tone: "success" },
  FAILED: { label: "✗ 复核未通过", tone: "danger" },
};

export const CLOSE_REASON_LABEL: Record<WoCloseReason, string> = {
  RESOLVED: "正常完结", INVALID: "误报", DUPLICATE: "重复单（已并单）", WITHDRAWN: "撤单（关联告警已自动恢复）",
};

export const BEARER: StatusMap<WoCostBearer> = {
  SITE: { label: "站点承担", tone: "info" },
  AGENT: { label: "代理承担", tone: "warning" },
};

export const ALARM_LEVEL: StatusMap<AlarmLevel> = {
  CRITICAL: { label: "▲ 严重", tone: "danger" },
  WARN: { label: "▪ 警告", tone: "warning" },
  INFO: { label: "▫ 提示", tone: "muted" },
};

/** 关联告警在复核意义上的状态：关闭 = 已恢复；其余 = 仍在。 */
export const ALARM_RECOVERY: StatusMap<"RECOVERED" | "ACTIVE"> = {
  RECOVERED: { label: "✓ 已恢复", tone: "success" },
  ACTIVE: { label: "✗ 仍在", tone: "danger" },
};

/**
 * 处理时间线的动作名（后端 wo_dispatch.action / wo_handle 派生的 action）。
 * 认不出的原样显示 —— 后端加了新动作，界面上看得到那个值，比显示成空好查。
 */
export const TIMELINE_ACTION: StatusMap<string> = {
  CREATE: { label: "建单", tone: "muted" },
  DISPATCH: { label: "派单", tone: "default" },
  ACCEPT: { label: "接单", tone: "default" },
  REASSIGN: { label: "改派", tone: "warning" },
  TAKEOVER: { label: "平台接管", tone: "warning" },
  REJECT: { label: "驳回", tone: "danger" },
  HANDLE: { label: "现场处理", tone: "info" },
  COMPLETE: { label: "完工", tone: "success" },
  REVIEW: { label: "复核", tone: "info" },
  CLOSE: { label: "验收关单", tone: "success" },
  REWORK: { label: "退回返工", tone: "danger" },
  WITHDRAW: { label: "撤单", tone: "muted" },
  MERGE: { label: "并单", tone: "muted" },
  PRIORITY_UP: { label: "升优先级", tone: "warning" },
  NOTE: { label: "备注", tone: "muted" },
};

/** 派单策略（时间线 DISPATCH 行的 note 就是它）。 */
export const DISPATCH_STRATEGY_LABEL: Record<string, string> = {
  OWNER: "按运维责任人", MANUAL: "人工派单", NEAREST: "就近", LOAD: "按负载", GRAB: "抢单",
};

/** 状态步骤条：主干五步（ACCEPTED 与 PROCESSING 合并展示为「处理」前后两步）。 */
export const WO_STEPS: Step[] = [
  { key: "CREATED", label: "创建" },
  { key: "DISPATCHED", label: "派单" },
  { key: "ACCEPTED", label: "接单" },
  { key: "PROCESSING", label: "处理" },
  { key: "DONE", label: "完工" },
  { key: "CLOSED", label: "验收" },
];
/** AUDITED 是关单事务里的过程态，步骤条上并入「验收」。 */
export const stepOf = (s: WorkOrder["status"]) => (s === "AUDITED" ? "CLOSED" : s);
