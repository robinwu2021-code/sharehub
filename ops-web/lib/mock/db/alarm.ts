// 告警域（对标简电云 A1~A4）：告警代码字典 / 告警记录 / 通知流水 / 通知规则 + 告警转工单。
// 关键：alarmCode（平台统一码）与 vendorErrorCode（厂商原始码）双列 —— 多厂商错误码归一化。
// 机柜一律引用 device.ts 的 cabinets，不复制机柜数据。
import type {
  AlarmCode, AlarmRecord, AlarmNotice, AlarmRule, AlarmAckResult, AlarmWorkOrderRef,
  AlarmNoticeResendPayload, PageQuery,

  AutoWorkOrderResult,} from "../../types";
import { LOCS, OPERATORS, p, iso, phone } from "./internal";
import { paginate, kwHit, upsert, nextNo, liveHit, archiveRow, unarchiveRow } from "./helpers";
import { cabinets } from "./device";
// 触达拉黑与脱敏口径住在 system.ts（系统设置域）：告警通知与业务通知走同一条触达链路，
// 拉黑名单必须共用一份 —— 各域自己维护一份「谁退订了」等于拉黑形同不存在。
import { notifyBlacklist, maskTarget } from "./system";

export const alarmCodes: AlarmCode[] = [
  { code: "OFFLINE", message: "柜机离线", level: "CRITICAL", suggestion: "检查网络与供电；10 分钟未恢复派现场工单", autoWorkOrder: true, archivedAt: null },
  { code: "SLOT_STUCK", message: "卡槽卡宝", level: "CRITICAL", suggestion: "远程弹仓一次；仍失败则锁槽并派维修", autoWorkOrder: true, archivedAt: null },
  { code: "LOCK_FAIL", message: "锁扣异常", level: "CRITICAL", suggestion: "锁槽止损，安排更换锁扣模块", autoWorkOrder: true, archivedAt: null },
  { code: "TEMP_HIGH", message: "机内温度过高", level: "CRITICAL", suggestion: "降功率并现场检查散热风道", autoWorkOrder: true, archivedAt: null },
  { code: "EJECT_TIMEOUT", message: "弹出超时", level: "WARN", suggestion: "复核指令回执；连续 3 次转维修工单", autoWorkOrder: true, archivedAt: null },
  { code: "BATTERY_LOW", message: "充电宝电量过低", level: "WARN", suggestion: "纳入下次补货路线，优先换宝", autoWorkOrder: false, archivedAt: null },
  { code: "SIGNAL_WEAK", message: "通信信号弱", level: "WARN", suggestion: "确认 4G 信号与天线位置，必要时挪机位", autoWorkOrder: false, archivedAt: null },
  { code: "HEARTBEAT_LOST", message: "心跳丢失", level: "WARN", suggestion: "观察 5 分钟；未恢复升级为 OFFLINE", autoWorkOrder: false, archivedAt: null },
  { code: "FW_UPGRADE_FAIL", message: "固件升级失败", level: "INFO", suggestion: "回滚上一版本，纳入下一批灰度", autoWorkOrder: false, archivedAt: null },
  { code: "SCREEN_FAULT", message: "广告屏异常", level: "INFO", suggestion: "不影响借还；并入巡检批量处理", autoWorkOrder: false, archivedAt: null },
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
    // i=4 这条刻意用一个**已在触达拉黑**的号码（值班人回复过 STOP）：规则照旧命中并发送失败，
    // 于是页面上会出现一条「看着能重发、其实必须被拒」的行 —— 少了它，拉黑分支永远走不到。
    target: i === 4 ? "+9715012345678"
      : ch === "SMS" ? phone(i)
      : ch === "EMAIL" ? p(["ops@sharehub.ae", "ops-dubai@sharehub.ae", "support@sharehub.ae"], i)
      : ch === "PUSH" ? p(OPERATORS, i)
      : "https://hooks.sharehub.ae/alarm",
    sentAt: iso(i * 3600_000), status: failed ? "FAILED" : "SENT",
    failReason: failed ? (i === 4 ? "触达拉黑名单命中（用户已退订）" : p(["短信网关超时", "目标号码停机", "Webhook 返回 500"], i)) : null,
    // 历史流水都是「原始发送」：重发只在运营点按钮时新增，seed 不预置重发链
    idempotencyKey: null, resendOf: null,
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
    status: i % 7 === 0 ? "INACTIVE" : "ACTIVE", archivedAt: null,
  };
});

export const listAlarmRecords = (q: PageQuery & { level?: string; status?: string } = {}) =>
  paginate(alarmRecords, q.page, q.size, (x) =>
    kwHit(q.keyword, x.alarmNo, x.cabinetNo, x.siteName, x.alarmCode, x.vendorErrorCode, x.workOrderNo) &&
    (!q.level || x.level === q.level) && (!q.status || x.status === q.status));
export const listAlarmNotices = (q: PageQuery = {}) => paginate(alarmNotices, q.page, q.size, (x) => kwHit(q.keyword, x.noticeNo, x.alarmNo, x.target));
export const listAlarmCodes = (q: PageQuery = {}) =>
  paginate(alarmCodes, q.page, q.size, (x) => liveHit(x, q.showArchived) && kwHit(q.keyword, x.code, x.message, x.suggestion));
export const listAlarmRules = (q: PageQuery = {}) =>
  paginate(alarmRules, q.page, q.size, (x) => liveHit(x, q.showArchived) && kwHit(q.keyword, x.ruleNo, x.alarmCode, x.target));
export const saveAlarmCode = (x: Partial<AlarmCode>) => upsert(alarmCodes, x, "code", () => nextNo("ALARM_CODE_", alarmCodes, 1));
export const saveAlarmRule = (x: Partial<AlarmRule>) => upsert(alarmRules, x, "ruleNo", () => nextNo("AR", alarmRules, 600));

/**
 * 告警转工单（mock）：生成关联工单号并置为已受理。
 *
 * 以 alarmNo 为幂等键，与后端 AlarmService#toWorkOrder 一致：已转过的**沿用原工单号**并回
 * `created: false`。回 WorkOrderRef 而不是整行 —— 之前 mock 回整行掩盖了契约与后端的错配，
 * 单测和 mock 都绿，只有接真后端才炸。
 */
export function raiseAlarmWorkOrder(alarmNo: string): AlarmWorkOrderRef {
  const a = alarmRecords.find((x) => x.alarmNo === alarmNo);
  if (!a) throw new Error(`告警不存在：${alarmNo}`);
  const existed = a.workOrderNo;
  if (!existed) {
    a.workOrderNo = nextNo("WO", alarmRecords.filter((x) => x.workOrderNo), 70200);
    a.status = "ACKED";
  }
  return { alarmNo: a.alarmNo, woNo: a.workOrderNo!, created: !existed };
}

/**
 * 告警 → 自动开工单联动（mock）。
 *
 * 这是「告警规则引擎」缺的最后一环：`AlarmCode.autoWorkOrder` 这个开关一直存在、
 * 告警转工单端点也一直存在且幂等，但**没有任何东西把两者连起来** —— 开关形同摆设。
 *
 * 口径：
 *  · 只处理**未关闭**且**尚无工单**的告警（CLOSED 的历史告警不该被批量翻出来开单）；
 *  · 是否开单只看告警码字典的 `autoWorkOrder`，不看告警等级 —— 等级决定紧急度，
 *    是否需要现场处置是码字典的业务判断（如 BATTERY_LOW 是 WARN 但不必开单）；
 *  · 逐条复用 `raiseAlarmWorkOrder`，因此**天然幂等**：重复点只会 skipped++，
 *    不会给同一个告警开出第二张单。一个反复上报的故障刷出一堆重复工单是运维成本事故。
 */
export function autoRaiseWorkOrders(): AutoWorkOrderResult {
  const autoCodes = new Set(alarmCodes.filter((c) => c.autoWorkOrder).map((c) => c.code));
  const targets = alarmRecords.filter((a) => a.status !== "CLOSED" && autoCodes.has(a.alarmCode));
  const created: { alarmNo: string; woNo: string }[] = [];
  let skipped = 0;
  for (const a of targets) {
    const r = raiseAlarmWorkOrder(a.alarmNo);
    if (r.created) created.push({ alarmNo: r.alarmNo, woNo: r.woNo });
    else skipped++;
  }
  return { eligible: targets.length, created, skipped };
}

/**
 * 确认告警（mock）：OPEN → ACKED，可带处置备注。
 *
 * 刻意**不做幂等**：后端 AlarmStateMachine 只认 OPEN --ACK--> ACKED，已受理/已关闭再确认会被拒（409）。
 * mock 同样抛错，否则前端会以为「重复确认没问题」，切到真后端才发现按钮该隐藏。
 */
export function ackAlarm(alarmNo: string, remark?: string): AlarmAckResult {
  const a = alarmRecords.find((x) => x.alarmNo === alarmNo);
  if (!a) throw new Error(`告警不存在：${alarmNo}`);
  if (a.status !== "OPEN") throw new Error("仅待处理（OPEN）的告警可确认");
  a.status = "ACKED";
  if (remark?.trim()) a.remark = remark.trim(); // 备注为空则保留原上报说明，不要抹掉
  return { alarmNo: a.alarmNo, status: a.status };
}

// ============================================================================
// 告警通知重发（拍板 #6）
// ----------------------------------------------------------------------------
// 口径与系统设置「发送记录 › 重发」（system.ts#resendNotifyLog）逐条对齐 —— 同一条触达链路，
// 两个入口若判得不一样，运营就会拿告警页绕过发送记录页的限制。
// 幂等键在**全部校验通过之后**才登记：校验失败就烧掉键，运营改完再点就永远发不出去。
// ============================================================================
export class AlarmNoticeSendError extends Error {
  constructor(msg: string) { super(msg); this.name = "AlarmNoticeSendError"; }
}

/** 已用过的幂等键。与发送记录各自一套：两边编号空间不同，键不会撞。 */
const usedAlarmNoticeKeys = new Set<string>(
  alarmNotices.map((x) => x.idempotencyKey).filter((k): k is string => !!k),
);

/** 触达拉黑命中（且未到期）。名单里存的是脱敏值，而告警通知存原值，故两种形态都比一遍。 */
const blacklistHit = (n: AlarmNotice) =>
  notifyBlacklist.find((b) =>
    (b.target === n.target || b.target === maskTarget(n.target)) &&
    (b.channel === "ALL" || b.channel === n.channel) &&
    (!b.expireAt || new Date(b.expireAt).getTime() > Date.now()));

/**
 * 重发告警通知：**新增**一条流水并返回它，原记录一字不改（审计要看得见「发了两次」）。
 *
 * 只允许重发失败通知：成功的再发一遍就是重复轰炸值班人 + 重复计费，要补发请改通知规则。
 * 告警已关闭时也拒绝 —— 事已了结还去催值班人，是纯噪音，长期会让人把告警通知静音。
 * 目标已在触达拉黑（且未到期）时拒绝：对退订号码重发既违规又白烧钱。
 */
export function resendAlarmNotice(noticeNo: string, x: AlarmNoticeResendPayload): AlarmNotice {
  const key = (x?.idempotencyKey ?? "").trim();
  if (!key) throw new AlarmNoticeSendError("重发必须携带幂等键（idempotencyKey）——重复提交会重复触达并重复计费");
  const src = alarmNotices.find((n) => n.noticeNo === noticeNo);
  if (!src) throw new AlarmNoticeSendError(`告警通知 ${noticeNo} 不存在`);
  if (src.status !== "FAILED") throw new AlarmNoticeSendError(`通知 ${noticeNo} 是「已发送」，不允许重发——重复发送会重复触达并重复计费`);
  const alarm = alarmRecords.find((a) => a.alarmNo === src.alarmNo);
  if (alarm?.status === "CLOSED") throw new AlarmNoticeSendError(`告警 ${src.alarmNo} 已关闭，不再重发通知`);
  const blocked = blacklistHit(src);
  if (blocked) throw new AlarmNoticeSendError(`目标已在触达拉黑（${blocked.blockNo}），不允许重发`);
  if (usedAlarmNoticeKeys.has(key)) throw new AlarmNoticeSendError(`幂等键 ${key} 已提交过，拒绝重复发送`);
  usedAlarmNoticeKeys.add(key);

  // 渠道/目标/告警号一律沿用原记录：重发不给「顺手改个目标」的口子，否则它就成了不受规则约束的手动发信
  const fresh: AlarmNotice = {
    ...src,
    noticeNo: nextNo("AN", alarmNotices, 50000, "noticeNo"),
    sentAt: iso(0), status: "SENT", failReason: null,
    idempotencyKey: key, resendOf: src.noticeNo,
  };
  alarmNotices.unshift(fresh);
  return fresh;
}

// —— G1 软删除：告警代码 / 通知规则 ——
export const archiveAlarmCode = (code: string) => archiveRow(alarmCodes, "code", code);
export const unarchiveAlarmCode = (code: string) => unarchiveRow(alarmCodes, "code", code);
export const archiveAlarmRule = (no: string) => archiveRow(alarmRules, "ruleNo", no);
export const unarchiveAlarmRule = (no: string) => unarchiveRow(alarmRules, "ruleNo", no);
