import type {
  AssetDiff, AssetDiffKind, AssetDiffStatus, InventoryTransferDetail, Powerbank, PowerbankAction,
  QcItemType, QcRecord, QcReq, QcStatus, ReceiveResult, TransferItemType, PageResult,
} from "../../types";
import { POWERBANK_TRANSITIONS, canPowerbankAction, canTransferAction, TRANSFER_TRANSITIONS } from "../../types";
import { fail, notFound } from "../../biz-error";
import { cabinets, powerbanks, inventoryTransfers, transferItemsOf, transferItemStore, getInventoryTransfer } from "./device";
import { paginate } from "./helpers";

/**
 * 设备资产线的 mock（批次 5b，照后端 `QcServiceImpl` / `TransferOpsServiceImpl` / `PowerbankStateMachine`）：
 * 入库质检 · 调拨逐件明细 / 发货 / 逐件签收 · 资产差异 · 充电宝人工状态动作。
 *
 * 真改 db：质检结论写回设备、发货把机柜推到运输中、签收把机柜拉回在库并落差异 —— 重开能读回。
 */

const now = () => new Date().toISOString();

// ——— 入库质检 ———

/**
 * 设备的质检状态（后端 `dev_cabinet.qc_status` / `dev_powerbank.qc_status`）。
 * 前端 Cabinet / Powerbank 出参**没有这一列**（后端 DTO 未带出，见批次 5b 回报），故单独存。
 * 查不到 = null = 质检上线前就入库的存量，按放行处理。
 */
const qcStatus = new Map<string, QcStatus>();
export const qcStatusOf = (itemNo: string): QcStatus | null => qcStatus.get(itemNo) ?? null;
/** 供测试 / 种子：把某台设备摆到「待质检」等状态。 */
export const setQcStatus = (itemNo: string, s: QcStatus) => { qcStatus.set(itemNo, s); };

const qcRecords: QcRecord[] = [];
let qcSeq = 1;

/** 放行：存量 null 或已通过。PENDING / FAILED 不能发货调拨、不能上线。 */
export const qcCleared = (itemNo: string) => {
  const s = qcStatusOf(itemNo);
  return s == null || s === "PASSED";
};

/** 检查项定结论；人可以把「过了」判成不过（带原因），不能把「不过」判成过（同后端 `decide`）。 */
function decide(req: QcReq, checksPass: boolean): QcStatus {
  const explicit = req.result ? String(req.result).trim().toUpperCase() : null;
  if (explicit === "FAILED" || (explicit == null && !checksPass)) {
    if (!req.note?.trim()) fail("判不通过必须写原因", "Note required when failing", "الملاحظة مطلوبة");
    return "FAILED";
  }
  if (explicit != null && explicit !== "PASSED") fail(`结论非法：${explicit}`, `Invalid result: ${explicit}`, "نتيجة غير صالحة");
  if (!checksPass) fail("检查项没全过，不能判通过", "Checks failed, cannot pass", "لم تنجح الفحوصات");
  return "PASSED";
}

function record(itemType: QcItemType, itemNo: string, req: QcReq, result: QcStatus): QcRecord {
  const r: QcRecord = {
    qcNo: `QC${String(qcSeq++).padStart(6, "0")}`, itemType, itemNo,
    powerOn: req.powerOn ?? null, slotsOk: req.slotsOk ?? null,
    battery: req.battery ?? null, cycles: req.cycles ?? null,
    result, note: req.note?.trim() || null, inspectedBy: "admin", inspectedAt: now(),
  };
  qcRecords.unshift(r);
  qcStatus.set(itemNo, result);
  return r;
}

/** 机柜入库质检。**只在在库时能做**：已布放的设备出问题走故障 / 维修，不走入库质检。 */
export function inspectCabinet(cabinetNo: string, req: QcReq): QcRecord {
  const c = cabinets.find((x) => x.cabinetNo === cabinetNo);
  if (!c) notFound("机柜", "Cabinet", cabinetNo);
  if (c!.status !== "IN_STOCK") fail(`只有在库设备可以做入库质检，当前是「${c!.status}」`, "In-stock only", "في المخزون فقط");
  if (req?.powerOn == null || req.slotsOk == null) fail("缺少检查项：通电 / 仓位", "Missing powerOn / slotsOk", "بيانات ناقصة");
  return record("CABINET", cabinetNo, req, decide(req, !!req.powerOn && !!req.slotsOk));
}

/** 质检阈值（后端系统参数 device.qc.min_battery / max_cycles 的默认值）。 */
export const QC_MIN_BATTERY = 60;
export const QC_MAX_CYCLES = 500;

/**
 * 疑似丢失经核实：确认丢失。**只对打了疑似标记的宝开放** —— 与后端同一条闸：
 * 没被系统怀疑过就要标丢失，说明判断依据不在系统里。
 */
export function confirmPowerbankLost(powerbankNo: string, note?: string): Powerbank {
  const p = powerbanks.find((x) => x.powerbankNo === powerbankNo);
  if (!p) notFound("充电宝", "Powerbank", powerbankNo);
  if (!p!.suspectedLostAt) {
    fail(`${powerbankNo} 未被标记为疑似丢失，不能在此确认丢失`,
         "Not marked as suspected lost", "غير مُعلَّم كمفقود محتمل");
  }
  p!.status = "LOST";
  p!.cabinetNo = null;
  p!.slotIndex = null;
  p!.suspectedLostAt = null;
  void note;
  return { ...p! };
}

/** 疑似丢失经核实：已找回 / 误判。说明必填 —— 事后要能看出当时凭什么解除。 */
export function dismissPowerbankLost(powerbankNo: string, note: string): Powerbank {
  const p = powerbanks.find((x) => x.powerbankNo === powerbankNo);
  if (!p) notFound("充电宝", "Powerbank", powerbankNo);
  if (!p!.suspectedLostAt) {
    fail(`${powerbankNo} 未被标记为疑似丢失`, "Not marked as suspected lost", "غير مُعلَّم كمفقود محتمل");
  }
  if (!note || !note.trim()) {
    fail("请填写说明：在哪里找回，或为什么是误判", "Note required", "الملاحظة مطلوبة");
  }
  p!.suspectedLostAt = null;
  return { ...p! };
}

/** 充电宝入库质检。电量 ≥ 60 且循环 ≤ 500 才算检查项通过；质检时顺带回写电量与循环次数。 */
export function inspectPowerbank(powerbankNo: string, req: QcReq): QcRecord {
  const p = powerbanks.find((x) => x.powerbankNo === powerbankNo);
  if (!p) notFound("充电宝", "Powerbank", powerbankNo);
  if (p!.status !== "IN_STOCK") fail(`只有在库充电宝可以做入库质检，当前是「${p!.status}」`, "In-stock only", "في المخزون فقط");
  if (req?.battery == null || req.cycles == null) fail("缺少检查项：电量 / 循环次数", "Missing battery / cycles", "بيانات ناقصة");
  const result = decide(req, req.battery >= QC_MIN_BATTERY && req.cycles <= QC_MAX_CYCLES);
  p!.battery = req.battery;
  p!.cycles = req.cycles;
  return record("POWERBANK", powerbankNo, req, result);
}

export const listQcRecords = (itemNo: string): QcRecord[] => qcRecords.filter((r) => r.itemNo === itemNo);

// ——— 充电宝人工状态动作 ———

/**
 * 按迁移表推进充电宝状态（非法迁移抛错，同后端 `PowerbankStateMachine.next`）。
 * 位置随生命周期同步：只有在仓 / 故障（在柜待取）保留柜位，其余清空（同后端 `applyLocation`）。
 */
export function transitPowerbank(powerbankNo: string, action: PowerbankAction): Powerbank {
  const p = powerbanks.find((x) => x.powerbankNo === powerbankNo);
  if (!p) notFound("充电宝", "Powerbank", powerbankNo);
  const t = POWERBANK_TRANSITIONS[action];
  if (!canPowerbankAction(p!.status, action)) {
    fail(
      `充电宝 ${powerbankNo} 当前是「${p!.status}」，不能${t.label}（允许自：${t.from.join(" / ")}）`,
      `Illegal transition: ${p!.status} --${t.event}-->`, `انتقال غير مسموح`,
    );
  }
  p!.status = t.to;
  if (t.to !== "IN_CABINET" && t.to !== "FAULT") {
    p!.slotIndex = null;
  }
  return p!;
}

// ——— 调拨作业 ———

const findTransfer = (transferNo: string) => {
  const t = inventoryTransfers.find((x) => x.transferNo === transferNo);
  if (!t) notFound("调拨单", "Transfer", transferNo);
  return t!;
};

/**
 * 能不能装车：存在、在库、质检已过（同后端 `checkShippable`）。
 *
 * <p>⚠️ mock 种子单据的明细号按单号推算（`PB20xxx`），大多不在只有 30 块宝的主数据里。
 * 这类**主数据查无**的件号只在种子单据里出现，跳过在库校验（否则种子草稿单一张都发不出去）；
 * 主数据里有的件照后端规则严格校验。经「设定明细」录入的件号必须在主数据里（见 {@link setTransferItems}）。
 */
function checkShippable(itemType: TransferItemType | null, no: string, strict: boolean) {
  if (itemType === "CABINET") {
    const c = cabinets.find((x) => x.cabinetNo === no);
    if (!c) { if (strict) notFound("机柜", "Cabinet", no); return; }
    if (c.status !== "IN_STOCK") fail(`${no} 不在库（当前「${c.status}」），不能装车`, `${no} is not in stock`, "ليس في المخزون");
  } else {
    const p = powerbanks.find((x) => x.powerbankNo === no);
    if (!p) { if (strict) notFound("充电宝", "Powerbank", no); return; }
    if (p.status !== "IN_STOCK") fail(`${no} 不在库（当前「${p.status}」），不能装车`, `${no} is not in stock`, "ليس في المخزون");
  }
  if (!qcCleared(no)) fail(`${no} 质检未通过，不能发货`, `${no} has not passed QC`, "لم يجتز الفحص");
}

/** 草稿期设定明细（整体替换）。件数随之回写单头。 */
export function setTransferItems(transferNo: string, itemNos: string[]): InventoryTransferDetail {
  const t = findTransfer(transferNo);
  if (t.status !== "DRAFT") fail(`只有草稿单能改明细，当前是「${t.status}」`, "Draft only", "المسودة فقط");
  const nos = [...new Set((itemNos ?? []).map((s) => s?.trim()).filter((s): s is string => !!s))];
  if (nos.length === 0) fail("明细不能为空", "itemNos is required", "القائمة فارغة");
  const itemType = (t.itemType as TransferItemType | null) ?? "POWERBANK";
  for (const no of nos) checkShippable(itemType, no, true);
  transferItemStore.set(transferNo, nos.map((itemNo) => ({ transferNo, itemNo, checked: false })));
  t.powerbankCount = nos.length;
  return getInventoryTransfer(transferNo);
}

/** 发货：DRAFT → IN_TRANSIT。**发货这一刻再核一遍**：建明细之后到装车之间，柜子可能被调走或质检被判不过。 */
export function shipTransfer(transferNo: string): InventoryTransferDetail {
  const t = findTransfer(transferNo);
  if (!canTransferAction(t.status, "ship")) {
    fail(`调拨单当前是「${t.status}」，不能${TRANSFER_TRANSITIONS.ship.label}`, "Illegal transition", "انتقال غير مسموح");
  }
  const itemType = (t.itemType as TransferItemType | null) ?? "POWERBANK";
  const items = transferItemsOf(transferNo);
  for (const i of items) checkShippable(itemType, i.itemNo, false);
  t.status = "IN_TRANSIT";
  if (itemType === "CABINET") {
    for (const i of items) {
      const c = cabinets.find((x) => x.cabinetNo === i.itemNo);
      if (c) c.status = "IN_TRANSIT";
    }
  }
  return getInventoryTransfer(transferNo);
}

const diffs: AssetDiff[] = [];
let diffSeq = 1;

function addDiff(sourceRef: string, kind: AssetDiffKind, itemType: TransferItemType, itemNo: string,
  expectedQty: number, actualQty: number): AssetDiff {
  const d: AssetDiff = {
    diffNo: `AD${String(diffSeq++).padStart(6, "0")}`, sourceType: "TRANSFER", sourceRef, kind, itemType, itemNo,
    siteNo: null, cabinetNo: null, expectedQty, actualQty, status: "OPEN",
    resolveNote: null, resolvedBy: null, resolvedAt: null, createdAt: now(),
  };
  diffs.unshift(d);
  return d;
}

/**
 * 逐件签收。**签收不因差异而卡住**：少了一台时整单拒收只会让另外九台也回不了库。
 * 签收照常完成，缺件 / 多件逐件落资产差异。机柜类收到的回到在库（落调入仓）。
 */
export function receiveTransfer(transferNo: string, receivedNos: string[], _note?: string): ReceiveResult {
  const t = findTransfer(transferNo);
  if (!canTransferAction(t.status, "receive")) {
    fail(`调拨单当前是「${t.status}」，不能${TRANSFER_TRANSITIONS.receive.label}`, "Illegal transition", "انتقال غير مسموح");
  }
  const items = transferItemsOf(transferNo);
  const got = new Set((receivedNos ?? []).map((s) => s?.trim()).filter((s): s is string => !!s));
  if (items.length > 0 && got.size === 0) fail("签收要逐件扫码：实收件号不能为空", "receivedNos is required", "مطلوب");
  const itemType = (t.itemType as TransferItemType | null) ?? "POWERBANK";
  t.status = "DONE";

  const missing: string[] = [];
  const out: AssetDiff[] = [];
  let received = 0;
  for (const i of items) {
    if (!got.has(i.itemNo)) {
      missing.push(i.itemNo);
      out.push(addDiff(transferNo, "MISSING", itemType, i.itemNo, 1, 0));
      continue;
    }
    received++;
    i.checked = true;
    if (itemType === "CABINET") {
      const c = cabinets.find((x) => x.cabinetNo === i.itemNo);
      if (c && c.status === "IN_TRANSIT") c.status = "IN_STOCK";
    }
  }
  const expected = new Set(items.map((i) => i.itemNo));
  const extra = [...got].filter((no) => !expected.has(no));
  for (const no of extra) out.push(addDiff(transferNo, "EXTRA", itemType, no, 0, 1));
  return { transferNo, status: "DONE", received, missing, extra, diffs: out };
}

// ——— 资产差异 ———

export function listAssetDiffs(
  q: { page?: number; size?: number; status?: AssetDiffStatus; sourceType?: string; sourceRef?: string } = {},
): PageResult<AssetDiff> {
  return paginate(diffs, q.page, q.size, (d) =>
    (!q.status || d.status === q.status)
    && (!q.sourceType || d.sourceType === q.sourceType.toUpperCase())
    && (!q.sourceRef || d.sourceRef === q.sourceRef));
}

/** 处理差异：写明去向结论。**结论必填**；已处理的不能再处理（同后端条件更新输了 409）。 */
export function resolveAssetDiff(diffNo: string, note: string): AssetDiff {
  if (!note?.trim()) fail("处理结论必填——没有结论的「已处理」等于把差异藏起来", "Note required", "الملاحظة مطلوبة");
  const d = diffs.find((x) => x.diffNo === diffNo);
  if (!d) notFound("资产差异", "Asset diff", diffNo);
  if (d!.status !== "OPEN") fail("该差异已处理过", "Already resolved", "تمت المعالجة");
  d!.status = "RESOLVED";
  d!.resolveNote = note.trim();
  d!.resolvedBy = "admin";
  d!.resolvedAt = now();
  return d!;
}
