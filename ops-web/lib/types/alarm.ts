// 覆盖范围：告警治理四件套（设备运营域 · P1，对标简电云 A1~A4）——
// 告警记录 / 通知流水 / 告警码字典 / 通知规则。

// 告警等级：提示 / 警告 / 严重
import type { Archivable } from "./common";

export type AlarmLevel = "INFO" | "WARN" | "CRITICAL";

// 告警记录：多厂商错误码归一化 —— alarmCode 是平台统一码，vendorErrorCode 是厂商原始码。
export interface AlarmRecord {
  alarmNo: string;
  cabinetNo: string;
  siteName: string;
  vendorCode: string; // 设备厂商（cd-tech / sd-power / chargenow）
  alarmCode: string; // 平台统一告警码，如 SLOT_STUCK
  vendorErrorCode: string; // 厂商原始错误码，各家风格不同（E203 / ERR-17 / 0x1F04）
  level: AlarmLevel;
  occurredAt: string;
  status: "OPEN" | "ACKED" | "CLOSED"; // 待处理 / 已受理 / 已关闭
  workOrderNo: string | null; // 关联工单号（转工单后回填）
  remark: string;
}

// 确认告警结果：回带落库后的状态，前端不自己猜 —— 状态迁移由后端状态机裁决（OPEN → ACKED）
export interface AlarmAckResult {
  alarmNo: string;
  status: AlarmRecord["status"];
}

/**
 * 告警转工单结果，镜像后端 `AlarmDtos.WorkOrderRef`。
 *
 * 此前契约把这个方法声明成返回整行 `AlarmRecord`、页面读 `r.workOrderNo` ——
 * 后端从来返回的是这个三字段对象，接真后端时 toast 会显示「已转工单 undefined」。
 * `created` 是幂等结果标志：该端点以 alarmNo 为幂等键，重复调用返回首次的 woNo 且 created=false，
 * 不回带它就无法区分「新建了一张单」与「已经有单了」，运营会重复派人到现场。
 */
export interface AlarmWorkOrderRef {
  alarmNo: string;
  woNo: string;
  created: boolean;
}

/** 自动开工单的执行结果。回带明细而不只回条数 —— 运营要能核对「到底给哪几条开了单」。 */
export interface AutoWorkOrderResult {
  /** 命中「自动开工单」告警码且未关闭的告警数 */
  eligible: number;
  /** 本次真正新建的工单（告警号 → 工单号） */
  created: { alarmNo: string; woNo: string }[];
  /** 已有工单被跳过的条数（幂等命中） */
  skipped: number;
}

// 告警通知：触达流水（谁/何时/何渠道/成功失败）
export interface AlarmNotice {
  noticeNo: string;
  alarmNo: string;
  channel: "SMS" | "EMAIL" | "PUSH" | "WEBHOOK";
  target: string; // 接收人（手机号/邮箱/工号/回调地址）
  sentAt: string;
  status: "SENT" | "FAILED";
  failReason: string | null;
  /**
   * 本条对外真发时用的幂等键（历史流水为 null：seed 里没有重发链）。
   * 落库而不只是当请求头，是为了「这条到底是哪次点击发出去的」可查 —— 排重复计费的账要看得见键。
   */
  idempotencyKey: string | null;
  resendOf: string | null; // 非空 = 本条是某条失败通知的补发，指向原通知号
}

/**
 * 重发告警通知的入参。**幂等键必带**（拍板 #6）：重发是「真的再发一条短信/邮件」，
 * 重复提交＝重复触达 + 重复计费，故键由前端在点确认的瞬间生成、服务端按键拒绝第二次。
 */
export interface AlarmNoticeResendPayload {
  idempotencyKey: string;
}

// 告警代码字典：比竞品多「建议处置」「是否自动开工单」——字典即处置预案
export interface AlarmCode extends Archivable {
  code: string;
  message: string;
  level: AlarmLevel;
  suggestion: string; // 建议处置
  autoWorkOrder: boolean; // 命中后是否自动开工单
}

// 通知规则：比竞品多「静默窗口」「升级策略」——防夜间轰炸与告警风暴
export interface AlarmRule extends Archivable {
  ruleNo: string;
  alarmCode: string;
  target: string; // 通知目标（角色/人/群）
  channel: AlarmNotice["channel"];
  method: "INSTANT" | "DIGEST"; // 即时 / 汇总
  quietStart: string; // 静默窗口起（HH:mm）
  quietEnd: string; // 静默窗口止（HH:mm）
  escalateMinutes: number; // N 分钟未处理则升级（0=不升级）
  status: "ACTIVE" | "INACTIVE";
}
