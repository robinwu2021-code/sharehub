import type {
  Cabinet, CabinetAction, Checklist, ChecklistItem, Protection, ProtectionReq, TrialRent, SignalCode,
} from "../../types";
import { CABINET_TRANSITIONS, canCabinetAction } from "../../types";
import { fail } from "../../biz-error";
import { cabinets, slotsOf } from "./device";
import { contracts, locations, sites } from "./location";
import { workOrders } from "./workorder";
import { qcStatusOf } from "./device-asset";

/**
 * 设备上线门禁 · 状态动作 · 试借还 · 保护动作的 mock。**逐条照后端**
 * `CabinetLifecycleServiceImpl` / `TrialRentServiceImpl` / `ProtectionServiceImpl`。
 *
 * <h3>门禁最硬的一关是试借还</h3>
 * 只查配置不试一次的话，**第一个真实用户就是试验品**，而那时现场已经没人了。
 * 所以 {@link goLiveGate} 里「试借还」是一条独立的必过项，而不是「配置齐了就算能用」。
 *
 * <h3>保护是可叠加的约束，不是状态</h3>
 * 同一台设备可能同时挂着信号触发的停租与人工挂的仓位禁用。
 * 解除时按**持有方**判权：SIGNAL / ALARM 挂的由系统撤，MANUAL 挂的才允许人工撤 ——
 * 不分持有方的话，运维手一抖把告警挂的停租解了，设备会在故障未恢复时重新接客。
 *
 * <h3>与此前 mock 的差别（批次 5b 对齐后端）</h3>
 * - 门禁项从 4 条（site/slots/trial/protection）改为后端的 10 条、key 同名；
 *   前端按 key 做任何判断时，mock 与真后端必须是同一套 key。
 * - 标故障 / 撤机 / 报废**原因必填**（后端 `requireReason`）。
 * - 标故障**不再自动挂停租保护**、撤机报废也不再顺手撤保护：后端没有这层联动，
 *   mock 多做一步的话，页面在 mock 下看到的保护列表在真后端下不存在。
 */

const protections: Protection[] = [];
const trials: TrialRent[] = [];
/** 试借还通过时刻 / 换点位时刻（后端 `trial_passed_at` / `bound_at`）：换点位后试借还要重做。 */
const trialPassedAt = new Map<string, string>();
let pSeq = 5000;
let tSeq = 6000;

const now = () => new Date().toISOString();
/** 在线口径：与后端同一个窗口（3 分钟内有心跳）。 */
const ONLINE_WINDOW_MS = 3 * 60_000;
/** 装宝比例区间（后端系统参数 device.load.min_ratio / max_ratio 的默认值）。 */
const LOAD_MIN = 0.5;
const LOAD_MAX = 0.8;

function find(deviceNo: string): Cabinet {
  const c = cabinets.find((x) => x.cabinetNo === deviceNo);
  if (!c) fail(`设备不存在：${deviceNo}`, `Device not found: ${deviceNo}`, `الجهاز غير موجود: ${deviceNo}`);
  if (c.archivedAt) fail("已归档的设备只读，先恢复再操作", "Archived device is read-only", "الجهاز المؤرشف للقراءة فقط");
  return c;
}

const pct = (r: number) => `${Math.round(r * 100)}%`;
const devHref = (no: string, tab?: string) => `/devices/detail?no=${no}${tab ? `&tab=${tab}` : ""}`;

// ——— 上线门禁 ———

/**
 * 上线门禁。十条与后端同 key、同判据，每条都带去处（fixHref，已是运营端路由）。
 * 只说「缺什么」而不给去处，门禁就成了拦路虎，人只会想绕过它。
 */
export function goLiveGate(deviceNo: string): Checklist {
  const c = find(deviceNo);
  const no = c.cabinetNo;
  const loc = c.locationNo ? locations.find((l) => l.locationNo === c.locationNo && !l.archivedAt) : undefined;
  const bound = !!loc;
  const site = c.siteNo ? sites.find((s) => s.siteNo === c.siteNo) : undefined;
  const siteOpen = !!site && ["PREPARING", "ACTIVE", "PAUSED"].includes(site.status);
  const contract = c.siteNo ? contracts.find((k) => k.siteNo === c.siteNo && k.status === "ACTIVE") : undefined;
  const hb = c.lastHeartbeatAt ? Date.parse(c.lastHeartbeatAt) : NaN;
  const online = c.onlineStatus === "ONLINE" && Number.isFinite(hb) && Date.now() - hb <= ONLINE_WINDOW_MS;
  const trial = trialPassedAt.has(no);
  const qc = qcStatusOf(no);
  const qcOk = qc == null || qc === "PASSED";
  // 勘测只卡「首台上线」：站点已在营业说明现场早验过了
  const firstLive = site?.status === "PREPARING";
  const surveyPassed = (site as { surveyPassed?: boolean | null } | undefined)?.surveyPassed;
  const survey = !firstLive || surveyPassed === true;
  const installed = workOrders.some((w) => w.type === "INSTALL" && w.cabinetNo === no
    && ["DONE", "AUDITED", "CLOSED"].includes(w.status));
  const inCabinet = slotsOf(no).filter((s) => s.powerbankNo).length;
  const ratio = c.slotTotal > 0 ? inCabinet / c.slotTotal : 0;
  const loadOk = c.slotTotal > 0 && ratio >= LOAD_MIN && ratio <= LOAD_MAX;
  const siteHref = c.siteNo ? `/operation/sites?no=${c.siteNo}` : "/operation/sites";

  const items: ChecklistItem[] = [
    { key: "QC", label: "入库质检", passed: qcOk,
      detail: qcOk ? (qc == null ? "存量设备免检" : "质检通过") : qc === "FAILED" ? "质检不通过" : "未做入库质检",
      fixHref: devHref(no, "qc") },
    { key: "SURVEY", label: "现场勘测", passed: survey,
      detail: !firstLive ? "站点已营业，免勘测" : surveyPassed == null ? "站点首台上线须先做现场勘测" : survey ? "勘测通过" : "最近一次勘测不通过",
      fixHref: `${siteHref}${c.siteNo ? "&" : "?"}tab=survey` },
    { key: "INSTALL_WO", label: "装机工单", passed: installed,
      detail: installed ? "装机工单已完工" : "没有已完工的装机工单",
      fixHref: `/work-orders?view=list&type=INSTALL&cabinetNo=${no}` },
    { key: "LOAD", label: "装宝比例", passed: loadOk,
      detail: c.slotTotal > 0
        ? `${inCabinet} / ${c.slotTotal} 仓（${pct(ratio)}）${loadOk ? "" : `，应在 ${pct(LOAD_MIN)}–${pct(LOAD_MAX)} 之间`}`
        : "仓位数未知，先在设备档案里补仓位数",
      fixHref: devHref(no, "slots") },
    { key: "LOCATION", label: "绑定点位", passed: bound,
      detail: bound ? (c.locationName ?? c.locationNo) : "未绑定点位或点位已归档",
      fixHref: devHref(no, "overview") },
    { key: "SITE_OPEN", label: "站点开放", passed: siteOpen,
      detail: !site ? "点位所属站点不存在" : siteOpen ? site.status : `站点状态为 ${site.status}，不能上线新设备`,
      fixHref: siteHref },
    { key: "CONTRACT", label: "生效合同", passed: !!contract,
      detail: contract ? contract.contractNo : "站点没有生效中的合同",
      fixHref: `/venues?tab=contracts&siteNo=${c.siteNo ?? ""}` },
    { key: "ONLINE", label: "设备在线", passed: online,
      detail: online ? `最近心跳 ${c.lastHeartbeatAt}` : !c.lastHeartbeatAt ? "从未收到心跳" : `最近心跳 ${c.lastHeartbeatAt}，已超过 3 分钟`,
      fixHref: devHref(no) },
    { key: "TRIAL", label: "试借还", passed: trial,
      detail: trial ? `通过于 ${trialPassedAt.get(no)}` : "未做试借还",
      fixHref: devHref(no, "trial") },
    // 计费方案：mock 没有「按设备匹配收费方案」的引擎，恒按默认方案命中（真后端按 PricingProbe 判）
    { key: "PRICE", label: "计费方案", passed: true, detail: "PP-DEFAULT-PB", fixHref: "/pricing" },
  ];
  return { allPassed: items.every((i) => i.passed), items };
}

/** 按迁移表推进；非法迁移抛错（同后端 `CabinetStateMachine.next` 的 400）。 */
function transit(c: Cabinet, action: CabinetAction): void {
  if (!canCabinetAction(c.status, action)) {
    const t = CABINET_TRANSITIONS[action];
    fail(
      `机柜 ${c.cabinetNo} 当前是「${c.status}」，不能${t.label}（允许自：${t.from.join(" / ")}）`,
      `Illegal transition: ${c.status} --${t.event}-->`,
      `انتقال غير مسموح: ${c.status}`,
    );
  }
  c.status = CABINET_TRANSITIONS[action].to;
}

function requireReason(reason?: string | null): string {
  const r = reason?.trim();
  if (!r) fail("原因必填——没有原因的状态变更事后没人说得清", "Reason is required", "السبب مطلوب");
  return r.slice(0, 512);
}

/** 上线。**先判边、再判门禁**（同后端：非法迁移 400 优先于门禁 409）；门禁不过就拒，不能只靠按钮禁用。 */
export function goLive(deviceNo: string): Cabinet {
  const c = find(deviceNo);
  if (!canCabinetAction(c.status, "goLive")) transit(c, "goLive");
  const gate = goLiveGate(deviceNo);
  if (!gate.allPassed) {
    const blocked = gate.items.filter((i) => !i.passed).map((i) => i.label).join("、");
    fail(`还不能上线：${blocked}`, `Cannot go live: ${blocked}`, `لا يمكن التشغيل: ${blocked}`);
  }
  transit(c, "goLive");
  // 站点筹备中 → 首台上线即转营业（后端 CabinetWentLiveEvent）
  const site = sites.find((s) => s.siteNo === c.siteNo);
  if (site?.status === "PREPARING") site.status = "ACTIVE";
  return c;
}

export function markDeviceFault(deviceNo: string, reason?: string): Cabinet {
  const r = requireReason(reason);
  const c = find(deviceNo);
  transit(c, "markFault");
  void r;
  return c;
}

export function repairDevice(deviceNo: string): Cabinet {
  const c = find(deviceNo);
  transit(c, "repair");
  return c;
}

/** 撤机回库：解绑点位 / 站点（代理归属保留）。换了点位，之前的试借还随之失效。 */
export function undeployDevice(deviceNo: string, reason?: string): Cabinet {
  requireReason(reason);
  const c = find(deviceNo);
  transit(c, "undeploy");
  c.locationNo = null;
  c.locationName = null;
  c.siteNo = null;
  trialPassedAt.delete(c.cabinetNo);
  return c;
}

/** 报废：**不可逆**。在用设备不能直接报废（先撤机），否则站点上留一台账面已报废、现场还在接客的柜子。 */
export function retireDevice(deviceNo: string, reason?: string): Cabinet {
  requireReason(reason);
  const c = find(deviceNo);
  transit(c, "retire");
  return c;
}

// ——— 试借还 ———

export const listTrialRents = (deviceNo: string): TrialRent[] => {
  find(deviceNo);
  return trials.filter((t) => t.cabinetNo === deviceNo).slice(0, 50);
};

/**
 * 发起试借还（同后端前置：设备在线、没有进行中的试借还、有一块可弹的宝且其仓位没被禁用/锁定）。
 *
 * <p>mock 直接推进到 `WAIT_RETURN` —— 真实链路是「下发弹仓 → 设备回执 → 等归还」，
 * 而 mock 没有设备。**不直接给 PASSED**：那会让门禁在离线开发时永远绿，
 * 于是「试借还」这一关等于不存在，上线后第一次遇到真设备才发现流程走不通。
 * 判定用 {@link finishTrialRent}（模拟设备回推）。
 */
export function startTrialRent(deviceNo: string): TrialRent {
  const c = find(deviceNo);
  if (c.onlineStatus !== "ONLINE") fail("设备离线，不能试借还——弹仓指令发不下去", "Device offline", "الجهاز غير متصل");
  const pending = trials.find((t) => t.cabinetNo === deviceNo && ["EJECTING", "WAIT_RETURN"].includes(t.status));
  if (pending) {
    fail(
      `设备 ${deviceNo} 还有一次试借还没结束（${pending.status}）`,
      `A trial rent is still in progress`, `هناك تجربة جارية`,
    );
  }
  const blocked = new Set(protections
    .filter((p) => p.cabinetNo === deviceNo && p.active && (p.action === "SLOT_DISABLE" || p.action === "SLOT_LOCK"))
    .map((p) => p.slotIndex));
  const slot = slotsOf(deviceNo)
    .filter((s) => s.powerbankNo && !blocked.has(s.slotIndex))
    .sort((a, b) => (b.battery ?? -1) - (a.battery ?? -1))[0];
  if (!slot) fail("柜里没有可弹出的充电宝（或所在仓位被禁用 / 锁定）", "No powerbank to eject", "لا يوجد باور بانك");
  const t: TrialRent = {
    trialNo: `TR${tSeq++}`, cabinetNo: deviceNo, slotIndex: slot.slotIndex,
    powerbankNo: slot.powerbankNo, status: "WAIT_RETURN",
    ejectedAt: now(), returnedAt: null, failReason: null, operator: "admin", createdAt: now(),
  };
  trials.unshift(t);
  return t;
}

/** 供测试与「模拟设备回推」用：把等待中的那次试借还判为通过 / 失败。通过即写回门禁。 */
export function finishTrialRent(trialNo: string, pass: boolean, failReason?: string): TrialRent {
  const t = trials.find((x) => x.trialNo === trialNo);
  if (!t) fail(`试借还不存在：${trialNo}`, `Trial not found`, `التجربة غير موجودة`);
  if (t.status !== "WAIT_RETURN") fail(`当前是「${t.status}」，不能判定`, `Not waiting`, `ليست في الانتظار`);
  t.status = pass ? "PASSED" : "FAILED";
  t.returnedAt = pass ? now() : null;
  t.failReason = pass ? null : (failReason ?? "归还失败");
  if (pass) trialPassedAt.set(t.cabinetNo, t.returnedAt!);
  return t;
}

// ——— 保护 ———

/** 默认含已解除的历史（同后端 `activeOnly` 默认 false）。 */
export const listProtections = (deviceNo: string, activeOnly = false): Protection[] => {
  find(deviceNo);
  return protections.filter((p) => p.cabinetNo === deviceNo && (!activeOnly || p.active));
};

/** 整柜级动作（后端 `ProtectionAction.wholeCabinet()`）：仓位号被忽略。 */
const WHOLE_CABINET = ["STOP_RENT", "DERATE"];

function applyProtectionInternal(
  c: Cabinet, req: ProtectionReq, holderType: Protection["holderType"], holderRef: string,
): Protection {
  const whole = WHOLE_CABINET.includes(req.action);
  const slot = whole || req.slotIndex == null ? null : Number(req.slotIndex);
  if (!whole && slot == null) {
    fail(`「${req.action}」作用于仓位，必须指定仓位号`, `Slot index required for ${req.action}`, "رقم الفتحة مطلوب");
  }
  if (slot != null && (!Number.isInteger(slot) || slot < 1 || slot > c.slotTotal)) {
    fail(`仓位号应在 1~${c.slotTotal} 之间`, `Slot out of range 1~${c.slotTotal}`, "رقم الفتحة خارج النطاق");
  }
  // 幂等（后端唯一键 cabinet+slot+action+holder）：同一持有方重复挂同一条，返回原来那条
  const existing = protections.find((p) => p.active && p.cabinetNo === c.cabinetNo && p.slotIndex === slot
    && p.action === req.action && p.holderType === holderType && p.holderRef === holderRef);
  if (existing) return existing;
  const p: Protection = {
    protectionNo: `PR${pSeq++}`, cabinetNo: c.cabinetNo, slotIndex: slot,
    action: req.action, holderType, holderRef, reason: req.reason ?? null,
    active: true, createdAt: now(), releasedAt: null, releaseReason: null,
  };
  protections.unshift(p);
  return p;
}

export function applyProtection(deviceNo: string, req: ProtectionReq): Protection {
  if (!req?.reason?.trim()) fail("保护原因必填——没有原因的保护没人敢解", "Reason required", "السبب مطلوب");
  if (!["STOP_RENT", "SLOT_DISABLE", "SLOT_LOCK", "DERATE"].includes(req.action)) {
    fail(`保护动作非法：${req.action}`, `Invalid action: ${req.action}`, "إجراء غير صالح");
  }
  const c = find(deviceNo);
  return applyProtectionInternal(c, { ...req, reason: req.reason.trim() }, "MANUAL", "admin");
}

/** 供测试与「模拟设备信号」用：以信号 / 告警身份挂一条保护（这类保护人工解不掉）。 */
export function applySystemProtection(
  deviceNo: string, req: ProtectionReq, holderType: "SIGNAL" | "ALARM", holderRef: string,
): Protection {
  return applyProtectionInternal(find(deviceNo), req, holderType, holderRef);
}

/**
 * 解除保护。**只能解人工挂的**，且原因必填 —— 见文件头。
 */
export function releaseProtection(protectionNo: string, reason?: string): Protection {
  if (!reason?.trim()) fail("解除原因必填", "Reason required", "السبب مطلوب");
  const p = protections.find((x) => x.protectionNo === protectionNo);
  if (!p) fail(`保护不存在：${protectionNo}`, `Protection not found`, `الحماية غير موجودة`);
  if (p.holderType !== "MANUAL") {
    fail(
      `这条保护由${p.holderType === "ALARM" ? "告警" : "设备信号"}持有，会在条件恢复时自动解除；` +
      `手工解除会让设备在故障未恢复时重新接客`,
      `Only MANUAL protections can be released by hand`,
      `يمكن رفع الحماية اليدوية فقط`,
    );
  }
  if (!p.active) fail("该保护已解除", "Already released", "تم رفعها بالفعل");
  p.active = false;
  p.releasedAt = now();
  p.releaseReason = reason.trim();
  return p;
}

// ——— 信号码字典 ———

/**
 * 设备信号码。**信号不是告警** —— 2026-09-25 裁决把设备错误码降为「信号」，
 * 告警中心只放业务告警。这里列的是信号，供排障与规则配置用。
 * 码表与后端 `dev_event_code` 同一批（节选）；「只记录不保护」后端写 `NONE`。
 */
export const listSignalCodes = (): SignalCode[] => [
  { code: "HEARTBEAT", name: "心跳", nameEn: "Heartbeat", category: "LINK", scope: "CABINET",
    protectiveAction: "NONE", clearsCode: null, feeds: "可借 / 可还（在线口径）" },
  { code: "POWER_ABNORMAL", name: "供电异常", nameEn: "Power abnormal", category: "LINK", scope: "CABINET",
    protectiveAction: "NONE", clearsCode: null, feeds: "可借 / 可还（离线根因）" },
  { code: "SLOT_STUCK", name: "仓位卡宝", nameEn: "Slot jammed", category: "SLOT", scope: "SLOT",
    protectiveAction: "AUTO_EJECT_RETRY", clearsCode: null, feeds: "可借 / 可还" },
  { code: "SLOT_EJECT_OK", name: "仓位弹出成功", nameEn: "Slot ejected", category: "SLOT", scope: "SLOT",
    protectiveAction: "NONE", clearsCode: "SLOT_STUCK", feeds: "—" },
  { code: "LOCK_FAIL", name: "锁扣异常", nameEn: "Lock failure", category: "SLOT", scope: "SLOT",
    protectiveAction: "SLOT_DISABLE", clearsCode: null, feeds: "可借 / 可还" },
  { code: "LOCK_OK", name: "锁扣自检通过", nameEn: "Lock self-test OK", category: "SLOT", scope: "SLOT",
    protectiveAction: "NONE", clearsCode: "LOCK_FAIL", feeds: "—" },
  { code: "BATTERY_ABNORMAL", name: "宝电池异常", nameEn: "Battery abnormal", category: "BATTERY", scope: "SLOT",
    protectiveAction: "SLOT_LOCK", clearsCode: null, feeds: "BATTERY_HAZARD" },
  { code: "BATTERY_UNHEALTHY", name: "宝健康衰减", nameEn: "Battery degraded", category: "BATTERY", scope: "POWERBANK",
    protectiveAction: "NONE", clearsCode: null, feeds: "资产（待报废）" },
  { code: "TEMP_HIGH", name: "机内温度高", nameEn: "Over temperature", category: "THERMAL", scope: "CABINET",
    protectiveAction: "DERATE", clearsCode: null, feeds: "CABINET_OVERHEAT" },
  { code: "TEMP_OK", name: "温度恢复", nameEn: "Temperature normal", category: "THERMAL", scope: "CABINET",
    protectiveAction: "NONE", clearsCode: "TEMP_HIGH", feeds: "—" },
  { code: "RETURN_SN_SEEN", name: "仓位识别到宝", nameEn: "Powerbank detected", category: "TXN", scope: "SLOT",
    protectiveAction: "NONE", clearsCode: null, feeds: "RETURN_NOT_RECOGNIZED · 试借还" },
];
