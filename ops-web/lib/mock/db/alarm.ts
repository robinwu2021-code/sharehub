// 告警域（对标简电云 A1~A4）：告警代码字典 / 告警记录 / 通知流水 / 通知规则 + 告警转工单。
// 关键：alarmCode（平台统一码）与 vendorErrorCode（厂商原始码）双列 —— 多厂商错误码归一化。
// 机柜一律引用 device.ts 的 cabinets，不复制机柜数据。
import type { AlarmCode, AlarmRecord, AlarmNotice, AlarmRule, PageQuery } from "../../types";
import { LOCS, OPERATORS, p, iso, phone } from "./internal";
import { paginate, kwHit, upsert, nextNo } from "./helpers";
import { cabinets } from "./device";

export const alarmCodes: AlarmCode[] = [
  { code: "OFFLINE", message: "柜机离线", level: "CRITICAL", suggestion: "检查网络与供电；10 分钟未恢复派现场工单", autoWorkOrder: true },
  { code: "SLOT_STUCK", message: "卡槽卡宝", level: "CRITICAL", suggestion: "远程弹仓一次；仍失败则锁槽并派维修", autoWorkOrder: true },
  { code: "LOCK_FAIL", message: "锁扣异常", level: "CRITICAL", suggestion: "锁槽止损，安排更换锁扣模块", autoWorkOrder: true },
  { code: "TEMP_HIGH", message: "机内温度过高", level: "CRITICAL", suggestion: "降功率并现场检查散热风道", autoWorkOrder: true },
  { code: "EJECT_TIMEOUT", message: "弹出超时", level: "WARN", suggestion: "复核指令回执；连续 3 次转维修工单", autoWorkOrder: true },
  { code: "BATTERY_LOW", message: "充电宝电量过低", level: "WARN", suggestion: "纳入下次补货路线，优先换宝", autoWorkOrder: false },
  { code: "SIGNAL_WEAK", message: "通信信号弱", level: "WARN", suggestion: "确认 4G 信号与天线位置，必要时挪机位", autoWorkOrder: false },
  { code: "HEARTBEAT_LOST", message: "心跳丢失", level: "WARN", suggestion: "观察 5 分钟；未恢复升级为 OFFLINE", autoWorkOrder: false },
  { code: "FW_UPGRADE_FAIL", message: "固件升级失败", level: "INFO", suggestion: "回滚上一版本，纳入下一批灰度", autoWorkOrder: false },
  { code: "SCREEN_FAULT", message: "广告屏异常", level: "INFO", suggestion: "不影响借还；并入巡检批量处理", autoWorkOrder: false },
];

// 厂商原始错误码风格各不相同：cd-tech=E2xx，sd-power=ERR-nn，chargenow=0x1Fxx
const vendorErr = (vendorCode: string, i: number) =>
  vendorCode === "cd-tech" ? `E${200 + (i % 40)}`
  : vendorCode === "sd-power" ? `ERR-${10 + (i % 30)}`
  : `0x1F${String(i % 100).padStart(2, "0")}`;

export const alarmRecords: AlarmRecord[] = Array.from({ length: 14 }, (_, i) => {
  const def = p(alarmCodes, i);
  const cab = p(cabinets, i * 3);
  const st = p(["OPEN", "OPEN", "ACKED", "CLOSED"] as const, i);
  return {
    alarmNo: `ALM${40000 + i}`, cabinetNo: cab.cabinetNo, siteName: cab.locationName ?? p(LOCS, i),
    vendorCode: cab.vendorCode, alarmCode: def.code, vendorErrorCode: vendorErr(cab.vendorCode, i),
    level: def.level, occurredAt: iso(i * 5400_000), status: st,
    workOrderNo: st === "OPEN" ? null : `WO${70000 + (i % 64)}`,
    remark: p(["心跳超时 10 分钟未恢复", "用户反馈取宝失败", "巡检现场发现", "厂商云回调上报", "监控脚本自动触发"], i),
  };
});

export const alarmNotices: AlarmNotice[] = Array.from({ length: 12 }, (_, i) => {
  const ch = p(["SMS", "EMAIL", "PUSH", "WEBHOOK"] as const, i);
  const failed = i % 5 === 4;
  return {
    noticeNo: `AN${50000 + i}`, alarmNo: p(alarmRecords, i).alarmNo, channel: ch,
    target: ch === "SMS" ? phone(i)
      : ch === "EMAIL" ? p(["ops@sharehub.ae", "ops-dubai@sharehub.ae", "support@sharehub.ae"], i)
      : ch === "PUSH" ? p(OPERATORS, i)
      : "https://hooks.sharehub.ae/alarm",
    sentAt: iso(i * 3600_000), status: failed ? "FAILED" : "SENT",
    failReason: failed ? p(["短信网关超时", "目标号码停机", "Webhook 返回 500"], i) : null,
  };
});

export const alarmRules: AlarmRule[] = Array.from({ length: 10 }, (_, i) => {
  const def = p(alarmCodes, i);
  const critical = def.level === "CRITICAL";
  return {
    ruleNo: `AR${600 + i}`, alarmCode: def.code,
    target: p(["运维值班组", "区域经理", "厂商对接人", "客服一线", "运营总监"], i),
    channel: p(["SMS", "PUSH", "EMAIL", "WEBHOOK"] as const, i),
    method: critical ? "INSTANT" : p(["INSTANT", "DIGEST"] as const, i),
    // 严重告警不设静默窗口（必须随时触达）；其余夜间静默，防轰炸
    quietStart: critical ? "" : "22:00", quietEnd: critical ? "" : "08:00",
    escalateMinutes: critical ? p([15, 30], i) : def.level === "WARN" ? 60 : 0,
    status: i % 7 === 0 ? "INACTIVE" : "ACTIVE",
  };
});

export const listAlarmRecords = (q: PageQuery & { level?: string; status?: string } = {}) =>
  paginate(alarmRecords, q.page, q.size, (x) =>
    kwHit(q.keyword, x.alarmNo, x.cabinetNo, x.siteName, x.alarmCode, x.vendorErrorCode, x.workOrderNo) &&
    (!q.level || x.level === q.level) && (!q.status || x.status === q.status));
export const listAlarmNotices = (q: PageQuery = {}) => paginate(alarmNotices, q.page, q.size, (x) => kwHit(q.keyword, x.noticeNo, x.alarmNo, x.target));
export const listAlarmCodes = (q: PageQuery = {}) => paginate(alarmCodes, q.page, q.size, (x) => kwHit(q.keyword, x.code, x.message, x.suggestion));
export const listAlarmRules = (q: PageQuery = {}) => paginate(alarmRules, q.page, q.size, (x) => kwHit(q.keyword, x.ruleNo, x.alarmCode, x.target));
export const saveAlarmCode = (x: Partial<AlarmCode>) => upsert(alarmCodes, x, "code", () => nextNo("ALARM_CODE_", alarmCodes, 1));
export const saveAlarmRule = (x: Partial<AlarmRule>) => upsert(alarmRules, x, "ruleNo", () => nextNo("AR", alarmRules, 600));

/** 告警转工单（mock）：生成关联工单号并置为已受理；已转过的沿用原工单号（幂等）。 */
export function raiseAlarmWorkOrder(alarmNo: string): AlarmRecord {
  const a = alarmRecords.find((x) => x.alarmNo === alarmNo)!;
  a.workOrderNo = a.workOrderNo ?? nextNo("WO", alarmRecords.filter((x) => x.workOrderNo), 70200);
  a.status = "ACKED";
  return a;
}
