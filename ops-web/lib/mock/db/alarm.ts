// 告警域（对标简电云 A1~A4）：告警代码字典 / 告警记录 / 通知流水 / 通知规则 + 告警转工单。
// 关键：alarmCode（平台统一码）与 vendorErrorCode（厂商原始码）双列 —— 多厂商错误码归一化。
// 机柜一律引用 device.ts 的 cabinets，不复制机柜数据。
import type {
  AlarmCode, AlarmRecord, AlarmNotice, AlarmRule, AlarmAckResult, AlarmWorkOrderRef,
  AlarmNoticeResendPayload, PageQuery,
  AutoWorkOrderResult, AlarmCloseReason, AlarmBusinessCode, AlarmBusinessInfo, AlarmLogItem,
  AlarmLogEvent, AlarmTodo, AlarmDomain, AlarmSubjectType, AlarmCause, AlarmDisposition,
  WorkOrderPriority, AlarmLevel,
} from "../../types";
import type { AlarmQ } from "../../api/query";
import { ALARM_TRANSITIONS, canAlarmAction, ALARM_CLOSE_REASONS } from "../../types";
import { LOCS, OPERATORS, p, iso, phone } from "./internal";
import { notFound, fail } from "@/lib/biz-error";
import { paginate, kwHit, upsert, nextNo, liveHit, archiveRow, unarchiveRow } from "./helpers";
import { cabinets } from "./device";
import { sites } from "./location";
// 触达拉黑与脱敏口径住在 system.ts（系统设置域）：告警通知与业务通知走同一条触达链路，
// 拉黑名单必须共用一份 —— 各域自己维护一份「谁退订了」等于拉黑形同不存在。
import { notifyBlacklist, maskTarget } from "./system";

type DeviceCodeSeed = Omit<AlarmCode, "messageEn" | "messageAr" | "business">;
const DEVICE_CODES: DeviceCodeSeed[] = [
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

/**
 * 业务告警码（2026-09-25 裁决 #3：设备错误码降为信号，告警中心只放业务告警）。
 *
 * 取自后端 V 迁移里的内置码（字段值逐个对过真后端 `/api/ops/alarms/codes`），挑每个域至少一条：
 * mock 下只种一个域的话，「按域分段」在页面上永远只有一段有数，等于没做。
 */
const bizCode = (
  code: string, message: string, messageEn: string, level: AlarmLevel, suggestion: string,
  b: Partial<AlarmBusinessCode> & Pick<AlarmBusinessCode, "domain" | "subjectType" | "evalType" | "basePriority" | "disposition">,
): AlarmCode => ({
  code, message, messageEn, messageAr: null, level, suggestion, autoWorkOrder: false, archivedAt: null,
  business: {
    holdMinutes: null, windowMinutes: null, threshold: null, businessHoursOnly: false, impactAdjust: true,
    ownerRole: null, woDelayMinutes: 0, mergeScope: "DEVICE", recoverRule: "SIGNAL_CLEAR", recoverHoldMinutes: 0,
    supersedes: null, enabled: true, builtin: true, ...b,
  },
});

export const BUSINESS_CODES: AlarmCode[] = [
  bizCode("SITE_UNRENTABLE", "站点借不到", "Site unrentable", "CRITICAL", "整站没有可借的宝：离线先联系场地查电源网络，无宝安排补货",
    { domain: "AVAILABILITY", subjectType: "SITE", evalType: "STATE", holdMinutes: 10, basePriority: "HIGH", disposition: "WORK_ORDER", mergeScope: "SITE", supersedes: "CABINET_UNRENTABLE" }),
  bizCode("CABINET_UNRENTABLE", "单柜借不到", "Cabinet unrentable", "WARN", "该柜无可借宝：离线查网络供电，无宝补货，停借查故障",
    { domain: "AVAILABILITY", subjectType: "CABINET", evalType: "STATE", holdMinutes: 10, basePriority: "MEDIUM", disposition: "WORK_ORDER", woDelayMinutes: 30 }),
  bizCode("SITE_UNRETURNABLE", "站点还不了", "Site unreturnable", "CRITICAL", "整站无空仓：满柜取宝，离线或锁故障派维修；C 端已提示最近可还站点",
    { domain: "RETURNABILITY", subjectType: "SITE", evalType: "STATE", holdMinutes: 10, basePriority: "HIGH", disposition: "WORK_ORDER", mergeScope: "SITE", supersedes: "CABINET_UNRETURNABLE" }),
  bizCode("RENT_NOT_DELIVERED", "付了款没拿到宝", "Paid but not delivered", "CRITICAL", "系统自动撤单并释放预授权；失败转客服主动联系用户",
    { domain: "TRANSACTION", subjectType: "ORDER", evalType: "STATE", holdMinutes: 5, basePriority: "HIGH", disposition: "AUTO_FIX", ownerRole: "CS" }),
  bizCode("BATTERY_HAZARD", "充电宝有安全隐患", "Battery hazard", "CRITICAL", "已锁仓；立即派人现场处置，通知场地联系人",
    { domain: "SAFETY", subjectType: "SLOT", evalType: "EVENT", basePriority: "URGENT", impactAdjust: false, disposition: "WORK_ORDER", recoverRule: "DISPOSITION_DONE" }),
  bizCode("POWERBANK_MISSING", "充电宝失联", "Powerbank missing", "WARN", "仓管核查最后位置（最后所在柜、最后订单、调拨单）；找回后告警自动消除",
    { domain: "ASSET", subjectType: "POWERBANK", evalType: "STATE", holdMinutes: 0, basePriority: "MEDIUM", disposition: "TODO", ownerRole: "OPS" }),
  bizCode("SITE_LOW_YIELD", "低效站点", "Low-yield site", "INFO", "迁机或撤场评估：先看摆放位置、场地客流与竞品，再决定迁到高效站点还是发起撤场",
    { domain: "REVENUE", subjectType: "SITE", evalType: "METRIC", threshold: 5, basePriority: "LOW", disposition: "TODO", ownerRole: "BD" }),
  bizCode("SITE_OWNER_MISSING", "站点无人运维", "Site owner missing", "WARN", "指定运维责任人，否则自动工单派不出去",
    { domain: "SERVICE", subjectType: "SITE", evalType: "STATE", holdMinutes: 0, basePriority: "MEDIUM", disposition: "TODO", ownerRole: "OPS" }),
  bizCode("SITE_WITHOUT_CONTRACT", "无合同在营业", "Site without contract", "CRITICAL", "补签合同或发起撤场；站点不自动停业",
    { domain: "PARTNER", subjectType: "SITE", evalType: "STATE", holdMinutes: 0, basePriority: "HIGH", disposition: "TODO", ownerRole: "BD" }),
  bizCode("CONTRACT_EXPIRING", "合同即将到期", "Contract expiring", "INFO", "续签或撤场评估：60 天起报，30 / 7 天升级；续签合同提交后自动消除",
    { domain: "PARTNER", subjectType: "CONTRACT", evalType: "STATE", holdMinutes: 0, threshold: 60, basePriority: "LOW", disposition: "TODO", ownerRole: "BD" }),
  bizCode("REFUND_FAILED", "退款失败", "Refund failed", "WARN", "财务处理，并通知客服",
    { domain: "FUND", subjectType: "PAYMENT", evalType: "EVENT", basePriority: "HIGH", disposition: "TODO", ownerRole: "FINANCE", recoverRule: "DISPOSITION_DONE" }),
  bizCode("WO_SLA_BREACH", "工单超时", "Work order SLA breach", "WARN", "升级通知；代理的单可由平台接管",
    { domain: "SERVICE", subjectType: "WORK_ORDER", evalType: "STATE", holdMinutes: 0, basePriority: "MEDIUM", disposition: "NOTIFY", ownerRole: "OPS", enabled: false }),
];

export const alarmCodes: AlarmCode[] = [
  ...DEVICE_CODES.map((c) => ({ ...c, messageEn: null, messageAr: null, business: null })),
  ...BUSINESS_CODES,
];

// 厂商原始错误码风格各不相同：cd-tech=E2xx，sd-power=ERR-nn，chargenow=0x1Fxx
const vendorErr = (vendorCode: string, i: number) =>
  vendorCode === "cd-tech" ? `E${200 + (i % 40)}`
  : vendorCode === "sd-power" ? `ERR-${10 + (i % 30)}`
  : `0x1F${String(i % 100).padStart(2, "0")}`;

const deviceAlarms: AlarmRecord[] = Array.from({ length: 14 }, (_, i) => {
  const def = p(DEVICE_CODES, i);
  const cab = p(cabinets, i * 3);
  const st = p(["OPEN", "OPEN", "ACKED", "CLOSED"] as const, i);
  return {
    alarmNo: `ALM${40000 + i}`, cabinetNo: cab.cabinetNo,
    siteNo: cab.siteNo ?? null, siteName: cab.locationName ?? p(LOCS, i),
    // 代理归属**跟机柜走**，不另生成：两处不一致的话，「这条告警派给谁」
    // 在告警页和设备台账上会给出两个答案，而页面上看不出矛盾。
    // 机柜 agentNo 为空 = 平台直营，告警也就没有代理。
    agentNo: cab.agentNo ?? null,
    /*
     * 来源三种都要有样本：DEVICE 占多数（设备自己上报），少量 OTA（固件投放
     * 过程中产生）与 RENT（租借流程判定）。只种 DEVICE 的话，「按来源分流」
     * 这件事在页面上永远是同一个值，等于没加这一列。
     */
    source: (i % 7 === 3 ? "OTA" : i % 5 === 2 ? "RENT" : "DEVICE") as AlarmRecord["source"],
    vendorCode: cab.vendorCode, alarmCode: def.code, vendorErrorCode: vendorErr(cab.vendorCode, i),
    level: def.level, occurredAt: iso(i * 5400_000), status: st,
    workOrderNo: st === "OPEN" ? null : `WO${70000 + (i % 64)}`,
    // 合并次数：多数告警只来一次，少数刷屏。两种都要有样本，
    // 否则「按次数排优先级」这件事在页面上看不出来。
    count: i % 5 === 0 ? 8 + (i % 40) : 1,
    remark: p(["心跳超时 10 分钟未恢复", "用户反馈取宝失败", "巡检现场发现", "厂商云回调上报", "监控脚本自动触发"], i),
    // 已关闭的种子行带上关闭信息 —— 否则「关闭了但看不出原因」在 mock 下复现不了，
    // 而这正是 2026-09-25 补关闭动作时要守住的那一点。
    closeReason: st === "CLOSED" ? p(["RESOLVED", "FALSE_ALARM", "SELF_HEALED"] as const, i) : null,
    closeNote: st === "CLOSED" ? "现场确认后关闭" : null,
    closedBy: st === "CLOSED" ? "admin" : null,
    closedAt: st === "CLOSED" ? iso(-2 - (i % 5)) : null,
    dedupKey: `${def.code}:${cab.cabinetNo}`,
    // 存量设备告警没有业务维度（后端同样回 null）—— 页面必须能渲染这种行，而不是只认业务告警
    business: null,
  };
});

/**
 * 业务告警种子（source=EVAL）。每行的主体、成因、处置都按码的配置来 ——
 * 随手编的话，「处置预览说开工单、而码配的是待办」这种自相矛盾在 mock 下就看不出来。
 *
 * 覆盖的形态：未处置（到期未开单）/ 已开单未关 / 挂待办 / 已自愈关闭 / 被站点级取代的柜级子告警 / 安全域。
 */
const bizAlarm = (
  i: number, code: string, st: AlarmRecord["status"],
  x: { subjectType: AlarmSubjectType; subjectNo: string; cause: AlarmCause; priority: WorkOrderPriority;
       siteIdx: number; cabinetNo?: string | null; disposition?: AlarmDisposition | null; ref?: string | null;
       parent?: string | null; inFlight?: number; closeReason?: AlarmCloseReason | null },
): AlarmRecord => {
  const def = BUSINESS_CODES.find((c) => c.code === code)!;
  const site = p(sites, x.siteIdx);
  const at = iso(i * 2400_000 + 600_000);
  const biz: AlarmBusinessInfo = {
    domain: def.business!.domain as AlarmDomain, subjectType: x.subjectType, subjectNo: x.subjectNo, cause: x.cause,
    priority: x.priority, impactScope: x.subjectType === "SITE" ? "SITE" : x.subjectType === "CABINET" ? "CABINET"
      : x.subjectType === "SLOT" ? "SLOT" : x.subjectType === "ORDER" ? "ORDER" : "ENTITY",
    impactPeriod: i % 3 === 0 ? "PEAK" : "OPEN", siteTier: p(["A", "B", "C"], x.siteIdx), inFlightOrders: x.inFlight ?? 0,
    dispositionType: x.disposition ?? null, dispositionRef: x.ref ?? null,
    firstOccurredAt: at, lastOccurredAt: at, dueAt: at, recoveredAt: st === "CLOSED" ? iso(i * 2400_000) : null,
    parentAlarmNo: x.parent ?? null,
  };
  return {
    alarmNo: `ALM${48000 + i}`, cabinetNo: x.cabinetNo ?? null, siteNo: site.siteNo, siteName: site.name,
    agentNo: site.agentNo ?? null, source: "EVAL", vendorCode: null, alarmCode: code,
    vendorErrorCode: null, level: def.level, occurredAt: at, status: st,
    workOrderNo: x.disposition === "WORK_ORDER" ? x.ref ?? null : null, count: 1, remark: null,
    dedupKey: `${code}:${x.subjectType}:${x.subjectNo}`,
    closeReason: st === "CLOSED" ? x.closeReason ?? "SELF_HEALED" : null,
    closeNote: st === "CLOSED" ? "信号恢复，系统自动关闭" : null,
    closedBy: st === "CLOSED" ? "SYSTEM" : null, closedAt: st === "CLOSED" ? iso(i * 2400_000) : null,
    business: biz,
  };
};

const cabOf = (i: number) => p(cabinets, i).cabinetNo;
const businessAlarms: AlarmRecord[] = [
  // 站点借不到：已开维修单，整站 · 高峰 → 紧急；下面挂一条被它取代的柜级告警
  bizAlarm(0, "SITE_UNRENTABLE", "OPEN", { subjectType: "SITE", subjectNo: sites[0].siteNo, cause: "OFFLINE", priority: "URGENT", siteIdx: 0, disposition: "WORK_ORDER", ref: "WO70003", inFlight: 3 }),
  bizAlarm(1, "CABINET_UNRENTABLE", "OPEN", { subjectType: "CABINET", subjectNo: cabOf(0), cabinetNo: cabOf(0), cause: "OFFLINE", priority: "MEDIUM", siteIdx: 0, parent: "ALM48000" }),
  // 站点还不了：到期未处置（开单延迟内）—— 「立即处置」的样本
  bizAlarm(2, "SITE_UNRETURNABLE", "OPEN", { subjectType: "SITE", subjectNo: sites[1].siteNo, cause: "FULL", priority: "HIGH", siteIdx: 1 }),
  bizAlarm(3, "CABINET_UNRENTABLE", "ACKED", { subjectType: "CABINET", subjectNo: cabOf(6), cabinetNo: cabOf(6), cause: "NO_STOCK", priority: "MEDIUM", siteIdx: 2 }),
  // 交易：付了款没拿到宝，自愈成功已关
  bizAlarm(4, "RENT_NOT_DELIVERED", "CLOSED", { subjectType: "ORDER", subjectNo: "ORD100004", cause: "CANCEL_FAILED", priority: "HIGH", siteIdx: 3, disposition: "AUTO_FIX", closeReason: "AUTO_FIXED" }),
  // 安全：只能随处置完成关闭
  bizAlarm(5, "BATTERY_HAZARD", "OPEN", { subjectType: "SLOT", subjectNo: `${cabOf(9)}#3`, cabinetNo: cabOf(9), cause: "HAZARD", priority: "URGENT", siteIdx: 4, disposition: "WORK_ORDER", ref: "WO70009" }),
  // 资产 / 经营 / 服务 / 合作 / 资金：落待办
  bizAlarm(6, "POWERBANK_MISSING", "OPEN", { subjectType: "POWERBANK", subjectNo: "PB200017", cause: "MISSING", priority: "MEDIUM", siteIdx: 5, disposition: "TODO", ref: "ATD8001" }),
  bizAlarm(7, "SITE_LOW_YIELD", "OPEN", { subjectType: "SITE", subjectNo: sites[6].siteNo, cause: "LOW_YIELD", priority: "LOW", siteIdx: 6, disposition: "TODO", ref: "ATD8002" }),
  bizAlarm(8, "SITE_OWNER_MISSING", "OPEN", { subjectType: "SITE", subjectNo: sites[7].siteNo, cause: "SLA_BELOW", priority: "MEDIUM", siteIdx: 7 }),
  bizAlarm(9, "SITE_WITHOUT_CONTRACT", "OPEN", { subjectType: "SITE", subjectNo: sites[8].siteNo, cause: "NO_CONTRACT", priority: "HIGH", siteIdx: 8, disposition: "TODO", ref: "ATD8003" }),
  bizAlarm(10, "CONTRACT_EXPIRING", "OPEN", { subjectType: "CONTRACT", subjectNo: "CT401", cause: "EXPIRING", priority: "LOW", siteIdx: 1, disposition: "TODO", ref: "ATD8004" }),
  bizAlarm(11, "REFUND_FAILED", "OPEN", { subjectType: "PAYMENT", subjectNo: "RF500011", cause: "CANCEL_FAILED", priority: "HIGH", siteIdx: 9, disposition: "TODO", ref: "ATD8005" }),
  bizAlarm(12, "SITE_UNRENTABLE", "CLOSED", { subjectType: "SITE", subjectNo: sites[10].siteNo, cause: "NO_STOCK", priority: "HIGH", siteIdx: 10 }),
];

/**
 * 存量设备告警在前、业务告警在后 —— 数组顺序只是种子顺序（alarm.test / 通知种子按下标取行），
 * 列表的展示顺序由 {@link listAlarmRecords} 按发生时刻倒序排，与后端一致。
 */
export const alarmRecords: AlarmRecord[] = [...deviceAlarms, ...businessAlarms];

// ————————————————————————————————————————————————————————————————
// 告警待办（后端 dev_alarm_todo）与时间线（dev_alarm_log）
// 放在这里而不是 business-alarm.ts：关闭告警要联动取消待办、写时间线，
// 而 business-alarm.ts 依赖本文件 —— 反过来引会成环。
// ————————————————————————————————————————————————————————————————

const todoSeed = (todoNo: string, alarmNo: string, roleCode: string, title: string, siteIdx: number, ago: number): AlarmTodo => ({
  todoNo, alarmNo, alarmCode: null, roleCode, assigneeNo: null, title, status: "OPEN",
  siteNo: p(sites, siteIdx).siteNo, createdAt: iso(ago), doneAt: null, doneBy: null, doneNote: null,
});

/** 待办种子与上面告警行的 dispositionRef 一一对应（ATD8001…8005）：点告警上的待办号能找到它。 */
export const alarmTodos: AlarmTodo[] = [
  todoSeed("ATD8001", "ALM48006", "OPS", "充电宝 PB200017 已失联 24 小时：核查最后所在柜、最后订单与调拨单", 5, 6 * 3600_000),
  todoSeed("ATD8002", "ALM48007", "BD", `站点 ${sites[6].siteNo} 近 30 天日均订单低于 5：评估迁机或撤场`, 6, 5 * 3600_000),
  todoSeed("ATD8003", "ALM48009", "BD", `站点 ${sites[8].siteNo} 在营业但没有生效合同：请续签 / 补签，或发起撤场`, 8, 4 * 3600_000),
  todoSeed("ATD8004", "ALM48010", "BD", "合同 CT401 将在 60 天内到期：发起续签或撤场评估", 1, 3 * 3600_000),
  todoSeed("ATD8005", "ALM48011", "FINANCE", "退款 RF500011 失败：重新发起退款并通知客服", 9, 2 * 3600_000),
];
const TODO_SEED = alarmTodos.map((t) => ({ ...t }));

const alarmLogs = new Map<string, AlarmLogItem[]>();
/** 写一条时间线。操作人缺省 admin（mock 的当前员工）。 */
export function logAlarm(alarmNo: string, event: AlarmLogEvent, note: string | null, operator = "admin"): void {
  const list = alarmLogs.get(alarmNo) ?? [];
  list.push({ event, note, operator, at: new Date().toISOString() });
  alarmLogs.set(alarmNo, list);
}
/** 某条告警的时间线。种子行按自身字段补出「成立 / 处置 / 关闭」三条，之后的动作逐条追加。 */
export function alarmTimeline(a: AlarmRecord): AlarmLogItem[] {
  if (!alarmLogs.has(a.alarmNo)) {
    const seed: AlarmLogItem[] = [{
      event: "OPEN", operator: "SYSTEM", at: a.business?.firstOccurredAt ?? a.occurredAt,
      note: a.business ? `成立：${a.business.cause ?? "-"} · 优先级 ${a.business.priority ?? "-"} · 影响 ${a.business.impactScope ?? "-"}/${a.business.impactPeriod ?? "-"}` : "设备上报",
    }];
    if (a.business?.parentAlarmNo) seed.push({ event: "SUPERSEDE", operator: "SYSTEM", at: a.occurredAt, note: `并入上层告警 ${a.business.parentAlarmNo}` });
    if (a.business?.dispositionType) seed.push({ event: "DISPOSE", operator: "SYSTEM", at: a.business.dueAt ?? a.occurredAt, note: `${a.business.dispositionType} ${a.business.dispositionRef ?? ""}`.trim() });
    if (a.status === "CLOSED" && a.closedAt) seed.push({ event: "CLOSE", operator: a.closedBy, at: a.closedAt, note: `${a.closeReason ?? ""} ${a.closeNote ?? ""}`.trim() });
    alarmLogs.set(a.alarmNo, seed);
  }
  return alarmLogs.get(a.alarmNo)!;
}

/** 关闭时的联动：该告警名下未完成的待办一并取消（后端 AlarmEngine#close 同口径）。 */
function cancelTodosOf(alarmNo: string, reason: string): void {
  for (const t of alarmTodos) {
    if (t.alarmNo === alarmNo && t.status === "OPEN") {
      t.status = "CANCELLED";
      t.doneAt = new Date().toISOString();
      t.doneBy = "SYSTEM";
      t.doneNote = `关联告警已关闭：${reason}`;
    }
  }
}

/** 仅供测试：时间线与待办回到种子。 */
export function __resetTimelines(): void {
  alarmLogs.clear();
  alarmTodos.splice(0, alarmTodos.length, ...TODO_SEED.map((t) => ({ ...t })));
}

/** 按码查业务配置（设备码 / 未登记的码为 null）。 */
export const businessOf = (code: string): AlarmBusinessCode | null =>
  alarmCodes.find((c) => c.code === code)?.business ?? null;

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

/** 逗号分隔的多值筛选（后端 status / domain 按逗号拆成 IN）。 */
const inList = (want: string | undefined, v: string | null | undefined) =>
  !want || want.split(",").includes(v ?? "");

/** 后端默认序：发生时刻倒序（同刻按插入倒序）。 */
const byOccurredDesc = () => [...alarmRecords].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt));

export const listAlarmRecords = (q: AlarmQ = {}) =>
  paginate(byOccurredDesc(), q.page, q.size, (x) =>
    kwHit(q.keyword, x.alarmNo, x.cabinetNo, x.siteName, x.alarmCode, x.vendorErrorCode, x.workOrderNo, x.siteNo, x.business?.subjectNo) &&
    (!q.level || x.level === q.level) && inList(q.status, x.status) &&
    (!q.cabinetNo || x.cabinetNo === q.cabinetNo) &&
    // 存量设备告警没有域：后端 `domain IN (...)` 同样筛不到它们
    (!q.domain || (!!x.business?.domain && inList(q.domain, x.business.domain))) &&
    (!q.subjectType || x.business?.subjectType === q.subjectType) &&
    (!q.siteNo || x.siteNo === q.siteNo) &&
    (!q.cause || x.business?.cause === q.cause) &&
    (!q.disposition || x.business?.dispositionType === q.disposition) &&
    (!q.from || x.occurredAt.slice(0, 10) >= q.from) && (!q.to || x.occurredAt.slice(0, 10) <= q.to) &&
    (!q.topOnly || !x.business?.parentAlarmNo));
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
  if (!a) throw notFound("告警", "Alarm", alarmNo);
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
  if (!a) throw notFound("告警", "Alarm", alarmNo);
  // 判据与目标状态都从 ALARM_TRANSITIONS 读 —— 此前两处各写一个字面量，
  // 后端改边时这里不会有任何提示。
  if (!canAlarmAction(a.status, "ack")) throw fail("仅待处理（OPEN）的告警可确认", "Only an OPEN alarm can be acknowledged", "يمكن الإقرار فقط بإنذار مفتوح (OPEN)");
  a.status = ALARM_TRANSITIONS.ack.to;
  if (remark?.trim()) a.remark = remark.trim(); // 备注为空则保留原上报说明，不要抹掉
  return { alarmNo: a.alarmNo, status: a.status };
}

/**
 * 关闭告警：OPEN / ACKED → CLOSED，**原因必填**。
 *
 * 与真后端 `AlarmServiceImpl.close` 同一套判据：状态机先拒非法迁移，
 * 原因先解析再落库 —— 值不合法时报「关闭原因非法」，而不是一路走到落库炸成别的话。
 */
export function closeAlarm(alarmNo: string, reason: AlarmCloseReason, note?: string): AlarmAckResult {
  const a = alarmRecords.find((x) => x.alarmNo === alarmNo);
  if (!a) throw notFound("告警", "Alarm", alarmNo);
  if (!canAlarmAction(a.status, "close")) {
    throw fail(`告警 ${alarmNo} 当前是「${a.status}」，不可关闭`,
      `Alarm ${alarmNo} is ${a.status} and cannot be closed`,
      `الإنذار ${alarmNo} في حالة ${a.status} ولا يمكن إغلاقه`);
  }
  // ALARM_CLOSE_REASONS 只列人工档：AUTO_FIXED / SUPERSEDED 只由系统写（后端 AlarmCloseReason#manual）
  if (!ALARM_CLOSE_REASONS.some((r) => r.value === reason)) {
    throw fail("关闭原因必填（已解决 / 误报 / 已自愈）",
      "A close reason is required (RESOLVED / FALSE_ALARM / SELF_HEALED)",
      "سبب الإغلاق مطلوب");
  }
  // 安全域与「随处置完成恢复」的业务告警，不许人工以「已解决」关 —— 与后端 closeManually 同一判据：
  // 隐患宝锁着仓，人工点一下「已解决」就把锁放了，而现场可能根本没人去过。
  const biz = a.source === "EVAL" ? businessOf(a.alarmCode) : null;
  if (reason === "RESOLVED" && biz && (biz.domain === "SAFETY" || biz.recoverRule === "DISPOSITION_DONE")) {
    throw fail("该告警只能随处置完成关闭（安全域 / 处置完成即恢复）",
      "This alarm can only be closed by completing its disposition",
      "لا يمكن إغلاق هذا الإنذار إلا بإكمال معالجته");
  }
  // 已解决 / 误报必须写说明：误报率是调规则的依据，没有说明的「误报」没人敢据此放宽阈值
  if ((reason === "RESOLVED" || reason === "FALSE_ALARM") && !note?.trim()) {
    throw fail("「已解决」「误报」必须填写说明", "A note is required for RESOLVED / FALSE_ALARM", "الملاحظة مطلوبة");
  }
  alarmTimeline(a); // 先落种子时间线，再改状态 —— 否则种子会按「已关闭」多补一条关闭
  a.status = ALARM_TRANSITIONS.close.to;
  a.closeReason = reason;
  if (note?.trim()) a.closeNote = note.trim();
  a.closedBy = "admin";
  a.closedAt = new Date().toISOString();
  logAlarm(a.alarmNo, "CLOSE", `${reason}${note?.trim() ? ` ${note.trim()}` : ""}`);
  cancelTodosOf(a.alarmNo, reason);
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
