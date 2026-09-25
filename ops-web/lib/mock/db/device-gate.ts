import type {
  Cabinet, Checklist, ChecklistItem, Protection, ProtectionReq, TrialRent, SignalCode,
} from "../../types";
import { fail } from "../../biz-error";
import { cabinets, slotsOf } from "./device";
import { sites } from "./location";

/**
 * 设备上线门禁 · 试借还 · 保护动作的 mock。
 *
 * <h3>门禁最硬的一关是试借还</h3>
 * 只查配置不试一次的话，**第一个真实用户就是试验品**，而那时现场已经没人了。
 * 所以 {@link goLiveGate} 里「试借还通过」是一条独立的必过项，
 * 而不是「配置齐了就算能用」。
 *
 * <h3>保护是可叠加的约束，不是状态</h3>
 * 同一台设备可能同时挂着信号触发的停租与人工挂的仓位禁用。
 * 解除时按**持有方**判权：SIGNAL / ALARM 挂的由系统撤，MANUAL 挂的才允许人工撤 ——
 * 不分持有方的话，运维手一抖把告警挂的停租解了，设备会在故障未恢复时重新接客。
 */

const protections: Protection[] = [];
const trials: TrialRent[] = [];
let pSeq = 5000;
let tSeq = 6000;

const now = () => new Date().toISOString();

function find(deviceNo: string): Cabinet {
  const c = cabinets.find((x) => x.cabinetNo === deviceNo);
  if (!c) fail(`设备不存在：${deviceNo}`, `Device not found: ${deviceNo}`, `الجهاز غير موجود: ${deviceNo}`);
  return c;
}

/** 该柜当前生效的保护。 */
const activeOf = (deviceNo: string) => protections.filter((p) => p.cabinetNo === deviceNo && p.active);

// ——— 上线门禁 ———

/**
 * 上线门禁。四条都真查数据。
 *
 * <p>与站点开业清单同形：**每条未通过都带 `fixHref`**，
 * 只说「缺什么」而不给去处，门禁就成了拦路虎，人只会想绕过它。
 */
export function goLiveGate(deviceNo: string): Checklist {
  const c = find(deviceNo);
  const site = sites.find((s) => s.siteNo === c.siteNo);
  const mySlots = slotsOf(deviceNo);
  const trialPassed = trials.some((t) => t.cabinetNo === deviceNo && t.status === "PASSED");
  const blocking = activeOf(deviceNo).filter((p) => p.action === "STOP_RENT");

  const items: ChecklistItem[] = [
    {
      key: "site", label: "已绑定站点且站点可营业",
      passed: !!site && ["PREPARING", "ACTIVE"].includes(site.status),
      detail: !site ? "还没绑定站点 —— 设备不知道自己在哪儿"
        : ["PREPARING", "ACTIVE"].includes(site.status) ? null
        : `站点当前是「${site.status}」，不能在它下面上线设备`,
      fixHref: site && ["PREPARING", "ACTIVE"].includes(site.status) ? null : "/devices?tab=inventory",
    },
    {
      key: "slots", label: "仓位已初始化",
      passed: mySlots.length > 0,
      detail: mySlots.length ? null : "没有仓位数据 —— 设备还没上报过仓位表",
      fixHref: mySlots.length ? null : `/devices/detail?no=${deviceNo}`,
    },
    {
      key: "trial", label: "试借还已通过",
      passed: trialPassed,
      // 这一条是门禁的核心：配置齐 ≠ 能用
      detail: trialPassed ? null : "还没做过成功的试借还 —— 弹仓与回收都没被验证过，第一个真实用户会替你试",
      fixHref: trialPassed ? null : `/devices/detail?no=${deviceNo}`,
    },
    {
      key: "protection", label: "没有停租类保护",
      passed: blocking.length === 0,
      detail: blocking.length === 0 ? null
        : `还挂着 ${blocking.length} 条停租保护（${blocking.map((p) => p.holderType).join("/")}）`,
      fixHref: blocking.length === 0 ? null : `/devices/detail?no=${deviceNo}`,
    },
  ];
  return { allPassed: items.every((i) => i.passed), items };
}

/** 上线。**门禁不过就拒** —— 与后端同规则，不能只靠按钮禁用。 */
export function goLive(deviceNo: string): Cabinet {
  const c = find(deviceNo);
  if (c.status !== "IN_STOCK") {
    fail(
      `只有在库设备可以上线，当前是「${c.status}」`,
      `Only IN_STOCK devices can go live`, `فقط الأجهزة في المخزون`,
    );
  }
  const gate = goLiveGate(deviceNo);
  if (!gate.allPassed) {
    const blocked = gate.items.filter((i) => !i.passed).map((i) => i.label).join("、");
    fail(`还不能上线：${blocked}`, `Cannot go live: ${blocked}`, `لا يمكن التشغيل: ${blocked}`);
  }
  c.status = "DEPLOYED";
  return c;
}

export function markDeviceFault(deviceNo: string, reason?: string): Cabinet {
  const c = find(deviceNo);
  if (c.status === "RETIRED") fail("已报废的设备不能标记故障", "Retired device", "جهاز مُخرج من الخدمة");
  c.status = "FAULT";
  // 故障即停租：不挂保护的话，故障柜仍然接客
  applyProtectionInternal(deviceNo, { action: "STOP_RENT", reason: reason ?? "设备故障" }, "MANUAL", "fault");
  return c;
}

export function repairDevice(deviceNo: string): Cabinet {
  const c = find(deviceNo);
  if (c.status !== "FAULT") fail(`只有故障设备可以修复，当前是「${c.status}」`, "Only FAULT devices", "فقط الأجهزة المعطلة");
  c.status = "DEPLOYED";
  for (const p of activeOf(deviceNo).filter((x) => x.holderRef === "fault")) releaseInternal(p, "故障已修复");
  return c;
}

export function undeployDevice(deviceNo: string, reason?: string): Cabinet {
  const c = find(deviceNo);
  if (!["DEPLOYED", "FAULT"].includes(c.status)) {
    fail(`只有在用或故障设备可以撤机，当前是「${c.status}」`, "Only DEPLOYED/FAULT", "فقط المنشورة أو المعطلة");
  }
  c.status = "IN_STOCK";
  c.siteNo = null as unknown as string;
  for (const p of activeOf(deviceNo)) releaseInternal(p, reason ?? "设备撤机");
  return c;
}

export function retireDevice(deviceNo: string, reason?: string): Cabinet {
  const c = find(deviceNo);
  if (c.status === "DEPLOYED") {
    fail("在用设备不能直接报废——先撤机", "Undeploy before retiring", "قم بإلغاء النشر أولاً");
  }
  c.status = "RETIRED";
  for (const p of activeOf(deviceNo)) releaseInternal(p, reason ?? "设备报废");
  return c;
}

// ——— 试借还 ———

export const listTrialRents = (deviceNo: string): TrialRent[] => {
  find(deviceNo);
  return trials.filter((t) => t.cabinetNo === deviceNo);
};

/**
 * 发起试借还。
 *
 * <p>mock 直接推进到 `WAIT_RETURN` —— 真实链路是「下发弹仓 → 设备回执 → 等归还」，
 * 而 mock 没有设备。**不直接给 PASSED**：那会让门禁在离线开发时永远绿，
 * 于是「试借还」这一关等于不存在，上线后第一次遇到真设备才发现流程走不通。
 */
export function startTrialRent(deviceNo: string): TrialRent {
  const c = find(deviceNo);
  const pending = trials.find((t) => t.cabinetNo === deviceNo && ["EJECTING", "WAIT_RETURN"].includes(t.status));
  if (pending) {
    fail(
      `设备 ${deviceNo} 还有一次试借还没结束（${pending.status}）`,
      `A trial rent is still in progress`, `هناك تجربة جارية`,
    );
  }
  const slot = slotsOf(deviceNo).find((s) => s.powerbankNo);
  const t: TrialRent = {
    trialNo: `TR${tSeq++}`, cabinetNo: deviceNo, slotIndex: slot?.slotIndex ?? 1,
    powerbankNo: slot?.powerbankNo ?? null, status: "WAIT_RETURN",
    ejectedAt: now(), returnedAt: null, failReason: null, operator: "admin", createdAt: now(),
  };
  trials.unshift(t);
  void c;
  return t;
}

/** 供测试与「模拟归还」用：把等待中的那次试借还判为通过 / 失败。 */
export function finishTrialRent(trialNo: string, pass: boolean, failReason?: string): TrialRent {
  const t = trials.find((x) => x.trialNo === trialNo);
  if (!t) fail(`试借还不存在：${trialNo}`, `Trial not found`, `التجربة غير موجودة`);
  if (t.status !== "WAIT_RETURN") fail(`当前是「${t.status}」，不能判定`, `Not waiting`, `ليست في الانتظار`);
  t.status = pass ? "PASSED" : "FAILED";
  t.returnedAt = pass ? now() : null;
  t.failReason = pass ? null : (failReason ?? "归还失败");
  return t;
}

// ——— 保护 ———

export const listProtections = (deviceNo: string, activeOnly = true): Protection[] => {
  find(deviceNo);
  return protections.filter((p) => p.cabinetNo === deviceNo && (!activeOnly || p.active));
};

function applyProtectionInternal(
  deviceNo: string, req: ProtectionReq, holderType: Protection["holderType"], holderRef: string,
): Protection {
  const p: Protection = {
    protectionNo: `PR${pSeq++}`, cabinetNo: deviceNo, slotIndex: req.slotIndex ?? null,
    action: req.action, holderType, holderRef, reason: req.reason ?? null,
    active: true, createdAt: now(), releasedAt: null, releaseReason: null,
  };
  protections.unshift(p);
  return p;
}

export function applyProtection(deviceNo: string, req: ProtectionReq): Protection {
  find(deviceNo);
  if (!req?.action) fail("保护动作必填", "Action required", "الإجراء مطلوب");
  if (!req.reason?.trim()) fail("保护原因必填——没有原因的保护没人敢解", "Reason required", "السبب مطلوب");
  return applyProtectionInternal(deviceNo, req, "MANUAL", "admin");
}

function releaseInternal(p: Protection, reason: string): Protection {
  p.active = false;
  p.releasedAt = now();
  p.releaseReason = reason;
  return p;
}

/**
 * 解除保护。**只能解人工挂的** —— 见文件头。
 */
export function releaseProtection(protectionNo: string, reason?: string): Protection {
  const p = protections.find((x) => x.protectionNo === protectionNo);
  if (!p) fail(`保护不存在：${protectionNo}`, `Protection not found`, `الحماية غير موجودة`);
  if (!p.active) fail("该保护已解除", "Already released", "تم رفعها بالفعل");
  if (p.holderType !== "MANUAL") {
    fail(
      `这条保护由${p.holderType === "ALARM" ? "告警" : "设备信号"}持有，会在条件恢复时自动解除；` +
      `手工解除会让设备在故障未恢复时重新接客`,
      `Only MANUAL protections can be released by hand`,
      `يمكن رفع الحماية اليدوية فقط`,
    );
  }
  return releaseInternal(p, reason ?? "人工解除");
}

// ——— 信号码字典 ———

/**
 * 设备信号码。**信号不是告警** —— 2026-09-25 裁决把设备错误码降为「信号」，
 * 告警中心只放业务告警。这里列的是信号，供排障与规则配置用。
 */
export const listSignalCodes = (): SignalCode[] => [
  { code: "S_OFFLINE", name: "心跳丢失", nameEn: "Heartbeat lost", category: "通信", scope: "CABINET",
    protectiveAction: "STOP_RENT", clearsCode: "S_ONLINE", feeds: "站点借不到" },
  { code: "S_ONLINE", name: "心跳恢复", nameEn: "Heartbeat restored", category: "通信", scope: "CABINET",
    protectiveAction: null, clearsCode: null, feeds: null },
  { code: "S_SLOT_JAM", name: "仓位卡阻", nameEn: "Slot jammed", category: "仓位", scope: "SLOT",
    protectiveAction: "SLOT_DISABLE", clearsCode: "S_SLOT_OK", feeds: "还不了" },
  { code: "S_SLOT_OK", name: "仓位恢复", nameEn: "Slot recovered", category: "仓位", scope: "SLOT",
    protectiveAction: null, clearsCode: null, feeds: null },
  { code: "S_POWER_LOW", name: "供电异常", nameEn: "Power abnormal", category: "电源", scope: "CABINET",
    protectiveAction: "DERATE", clearsCode: "S_POWER_OK", feeds: "站点借不到" },
  { code: "S_POWER_OK", name: "供电恢复", nameEn: "Power restored", category: "电源", scope: "CABINET",
    protectiveAction: null, clearsCode: null, feeds: null },
];
