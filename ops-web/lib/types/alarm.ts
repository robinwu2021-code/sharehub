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

// 告警通知：触达流水（谁/何时/何渠道/成功失败）
export interface AlarmNotice {
  noticeNo: string;
  alarmNo: string;
  channel: "SMS" | "EMAIL" | "PUSH" | "WEBHOOK";
  target: string; // 接收人（手机号/邮箱/工号/回调地址）
  sentAt: string;
  status: "SENT" | "FAILED";
  failReason: string | null;
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
