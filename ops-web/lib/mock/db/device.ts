// 设备域：机柜 / 仓位 / 充电宝 / 实时监控 / 远程指令 / 调拨 / OTA / 供应商接入 / 设备日志 / 设备编码批次。
// cabinets 是全库的“机柜号来源”，其他域（订单/告警/客服/营销广告位…）一律通过 cabNo() 引用，不复制数据。
import type {
  Cabinet, Slot, Vendor, Powerbank, CabinetMonitor, CommandRecord,
  InventoryTransfer, OtaRollout, DeviceLog, DeviceCodeBatch, PageQuery,
} from "../../types";
import { VENDORS, LOCS, OPERATORS, p, iso } from "./internal";
import { paginate, kwHit, upsert, nextNo, liveHit, archiveRow, unarchiveRow } from "./helpers";

// —— 设备 ——
export const cabinets: Cabinet[] = Array.from({ length: 48 }, (_, i) => {
  const total = p([6, 8, 12], i);
  const online = i % 9 !== 0;
  return {
    cabinetNo: `CAB${1000 + i}`, sn: `SN${90000 + i}`, vendorCode: p(VENDORS, i),
    // 归属代理（A1）：号段与 agents（AG001–AG009）对齐；每 5 台留 1 台平台直营，
    // 划拨抽屉才有「未归属」的候选可选。这里刻意不 import agent.ts —— 保持
    // device → agent 的依赖方向单向（agent.ts 反过来引用 cabinets 反算设备数）。
    agentNo: i % 5 === 0 ? null : `AG${String((i % 9) + 1).padStart(3, "0")}`,
    model: p(["X6", "S8", "M12"], i), locationNo: `LOC${200 + (i % LOCS.length)}`,
    locationName: p(LOCS, i), slotTotal: total, availableCount: (i * 7) % (total + 1),
    onlineStatus: online ? "ONLINE" : "OFFLINE", status: i % 13 === 0 ? "FAULT" : "DEPLOYED",
    fwVersion: p(["1.2.0", "1.3.1", "1.4.0"], i), lastHeartbeatAt: online ? iso(i * 60000) : null,
    archivedAt: null,
  };
});
export function slotsOf(cabinetNo: string): Slot[] {
  const cab = cabinets.find((c) => c.cabinetNo === cabinetNo);
  const total = cab?.slotTotal ?? 8;
  // 仓位里的充电宝号一律取自 powerbanks 主数据（原先拼 `PB` + 机柜尾号 + 槽位，
  // 造出第三套 PB 号段，点进充电宝管理查无此宝）。
  const base = cabinets.findIndex((c) => c.cabinetNo === cabinetNo);
  return Array.from({ length: total }, (_, i) => {
    const filled = i < (cab?.availableCount ?? 0);
    return {
      slotIndex: i + 1, powerbankNo: filled ? p(powerbanks, base * 3 + i).powerbankNo : null,
      battery: filled ? 40 + ((i * 13) % 60) : null, lockStatus: filled ? "LOCKED" : "UNLOCKED",
      health: i === total - 1 && cab?.status === "FAULT" ? "FAULT" : "OK",
    };
  });
}

/** 跨域引用机柜号的统一入口（原 db.ts 私有 helper，不对外导出）。 */
export const cabNo = (i: number) => p(cabinets, i).cabinetNo;

// —— 供应商接入 ——
export const vendors: Vendor[] = [
  { vendorCode: "cd-tech", name: "CD Technology", accessMode: "TCP", status: "ENABLED", apiBase: null, deviceCount: cabinets.filter((c) => c.vendorCode === "cd-tech").length },
  { vendorCode: "sd-power", name: "SD Power", accessMode: "MQTT", status: "ENABLED", apiBase: null, deviceCount: cabinets.filter((c) => c.vendorCode === "sd-power").length },
  { vendorCode: "chargenow", name: "ChargeNow Cloud", accessMode: "HTTP_API", status: "ENABLED", apiBase: "https://api.chargenow.example", deviceCount: cabinets.filter((c) => c.vendorCode === "chargenow").length },
];

export const powerbanks: Powerbank[] = Array.from({ length: 30 }, (_, i) => {
  const st = p(["IN_CABINET", "IN_CABINET", "RENTED", "FAULT", "RETIRED"] as const, i);
  return {
    powerbankNo: `PB${20000 + i}`, cabinetNo: cabNo(i),
    battery: st === "RENTED" ? 20 + (i * 7) % 60 : 60 + (i * 11) % 40,
    status: st, health: st === "FAULT" ? "FAULT" : "OK", cycles: 40 + (i * 37) % 900,
    archivedAt: null,
  };
});
export const cabinetMonitors: CabinetMonitor[] = Array.from({ length: 24 }, (_, i) => {
  const online = i % 8 !== 0;
  return {
    cabinetNo: cabNo(i), locationName: p(LOCS, i), online,
    heartbeatAt: online ? iso(i * 45000) : iso(i * 3600_000),
    signal: online ? 55 + (i * 13) % 45 : 0, temp: 28 + (i * 3) % 18,
    faultCount: i % 5 === 0 ? (i % 3) + 1 : 0,
  };
});
export const commandRecords: CommandRecord[] = Array.from({ length: 30 }, (_, i) => {
  const type = p(["EJECT", "LOCK", "REBOOT", "LOCATE"] as const, i);
  return {
    commandId: `CMD${880000 + i}`, cabinetNo: cabNo(i), type,
    slotIndex: type === "EJECT" || type === "LOCK" ? (i % 8) + 1 : null,
    status: p(["ACKED", "ACKED", "SENT", "TIMEOUT", "FAILED"] as const, i),
    operator: p(OPERATORS, i), createdAt: iso(i * 900_000),
  };
});
export const inventoryTransfers: InventoryTransfer[] = Array.from({ length: 16 }, (_, i) => ({
  transferNo: `TR${60000 + i}`, fromLocation: p(LOCS, i), toLocation: p(LOCS, i + 2),
  powerbankCount: 5 + (i * 3) % 40, status: p(["DRAFT", "IN_TRANSIT", "DONE"] as const, i),
  operator: p(OPERATORS, i), createdAt: iso(i * 43200_000),
}));
export const otaRollouts: OtaRollout[] = Array.from({ length: 14 }, (_, i) => {
  const st = p(["PENDING", "RUNNING", "DONE", "ROLLBACK"] as const, i);
  return {
    rolloutNo: `OTA${5000 + i}`, fwVersion: p(["1.4.0", "1.4.1", "1.5.0", "2.0.0"], i),
    vendorCode: p(VENDORS, i), strategy: i % 3 === 0 ? "FULL" : "GRAY",
    progress: st === "DONE" ? 100 : st === "PENDING" ? 0 : 10 + (i * 13) % 80,
    status: st, createdAt: iso(i * 86400_000),
  };
});

// —— §1 设备日志：双流合一（COMMAND 下发 / REPORT 上报），按时间倒序 ——
const CMD_EVENTS = ["EJECT", "LOCK", "REBOOT", "FW_UPGRADE", "LOCATE"] as const;
const RPT_EVENTS = ["HEARTBEAT", "SLOT_STATE", "RETURN_DETECT", "BATTERY_LOW", "FAULT"] as const;

const cmdPayload = (ev: string, cab: string, i: number) => {
  const slot = (i % 8) + 1;
  switch (ev) {
    case "EJECT": return JSON.stringify({ cmd: "eject", cabinetNo: cab, slot, orderNo: `ORD${500000 + (i % 120)}`, ttlSec: 30 });
    case "LOCK": return JSON.stringify({ cmd: "lock", cabinetNo: cab, slot, reason: "SLOT_FAULT" });
    case "REBOOT": return JSON.stringify({ cmd: "reboot", cabinetNo: cab, delaySec: 5, operator: "admin" });
    case "FW_UPGRADE": return JSON.stringify({ cmd: "fw_upgrade", cabinetNo: cab, fromVersion: "1.3.1", toVersion: "1.4.0", pkgSize: 1843200 });
    default: return JSON.stringify({ cmd: "locate", cabinetNo: cab, buzzerSec: 3 });
  }
};
const rptPayload = (ev: string, cab: string, i: number) => {
  const slot = (i % 8) + 1;
  switch (ev) {
    case "HEARTBEAT": return JSON.stringify({ evt: "heartbeat", cabinetNo: cab, signal: 62 + (i % 30), temp: 31 + (i % 9), fwVersion: "1.4.0", availableCount: i % 9 });
    case "SLOT_STATE": return JSON.stringify({ evt: "slot_state", cabinetNo: cab, slot, powerbankNo: p(powerbanks, i).powerbankNo, battery: 40 + (i % 55), lock: "LOCKED" });
    case "RETURN_DETECT": return JSON.stringify({ evt: "return_detect", cabinetNo: cab, slot, powerbankNo: p(powerbanks, i).powerbankNo, orderNo: `ORD${500000 + (i % 120)}`, battery: 12 + (i % 40) });
    case "BATTERY_LOW": return JSON.stringify({ evt: "battery_low", cabinetNo: cab, slot, powerbankNo: p(powerbanks, i).powerbankNo, battery: 5 + (i % 8), threshold: 15 });
    default: return JSON.stringify({ evt: "fault", cabinetNo: cab, slot, code: "SLOT_STUCK", detail: "powerbank not ejected after 3 retries" });
  }
};

export const deviceLogs: DeviceLog[] = Array.from({ length: 42 }, (_, i) => {
  const isCmd = i % 2 === 0; // 下发/上报交替，构成可读的因果时间轴
  const cab = cabNo(i);
  const ev = isCmd ? p(CMD_EVENTS as unknown as string[], i >> 1) : p(RPT_EVENTS as unknown as string[], i >> 1);
  const bad = i % 11 === 3 ? "TIMEOUT" : i % 17 === 5 ? "FAILED" : "OK";
  return {
    logNo: `LOG${80000 + i}`,
    cabinetNo: cab,
    stream: isCmd ? "COMMAND" : "REPORT",
    direction: isCmd ? "DOWN" : "UP",
    eventType: ev,
    payload: isCmd ? cmdPayload(ev, cab, i) : rptPayload(ev, cab, i),
    vendorCode: p(VENDORS, i),
    occurredAt: iso(i * 900_000), // 递增偏移 = 时间倒序
    result: bad as DeviceLog["result"],
  };
});

// —— §2 设备编码：按批次 + 供应商归集，跟踪绑定进度 ——
export const deviceCodeBatches: DeviceCodeBatch[] = [
  { batchNo: "BC900", vendorCode: "cd-tech", codeType: "SN", rangeStart: "SN090000", rangeEnd: "SN090999", total: 1000, bound: 1000, producedAt: iso(210 * 86400_000), status: "BOUND" },
  { batchNo: "BC901", vendorCode: "cd-tech", codeType: "QR", rangeStart: "QRAE010001", rangeEnd: "QRAE011000", total: 1000, bound: 742, producedAt: iso(150 * 86400_000), status: "PARTIAL" },
  { batchNo: "BC902", vendorCode: "sd-power", codeType: "SN", rangeStart: "SN091000", rangeEnd: "SN091499", total: 500, bound: 500, producedAt: iso(120 * 86400_000), status: "BOUND" },
  { batchNo: "BC903", vendorCode: "sd-power", codeType: "QR", rangeStart: "QRAE020001", rangeEnd: "QRAE020600", total: 600, bound: 128, producedAt: iso(75 * 86400_000), status: "PARTIAL" },
  { batchNo: "BC904", vendorCode: "chargenow", codeType: "SN", rangeStart: "SN092000", rangeEnd: "SN092799", total: 800, bound: 0, producedAt: iso(40 * 86400_000), status: "PENDING" },
  { batchNo: "BC905", vendorCode: "chargenow", codeType: "QR", rangeStart: "QRSA030001", rangeEnd: "QRSA030400", total: 400, bound: 0, producedAt: iso(28 * 86400_000), status: "PENDING" },
  { batchNo: "BC906", vendorCode: "cd-tech", codeType: "QR", rangeStart: "QRAE019001", rangeEnd: "QRAE019200", total: 200, bound: 0, producedAt: iso(96 * 86400_000), status: "VOID" },
  { batchNo: "BC907", vendorCode: "sd-power", codeType: "SN", rangeStart: "SN093000", rangeEnd: "SN093299", total: 300, bound: 61, producedAt: iso(14 * 86400_000), status: "PARTIAL" },
];

// —— list / save ——
export const listPowerbanks = (q: PageQuery = {}) =>
  paginate(powerbanks, q.page, q.size, (x) => liveHit(x, q.showArchived) && kwHit(q.keyword, x.powerbankNo, x.cabinetNo));
export const listCabinetMonitor = (q: PageQuery = {}) => paginate(cabinetMonitors, q.page, q.size, (x) => kwHit(q.keyword, x.cabinetNo, x.locationName));
export const listCommandRecords = (q: PageQuery = {}) => paginate(commandRecords, q.page, q.size, (x) => kwHit(q.keyword, x.commandId, x.cabinetNo, x.operator));
export const listInventoryTransfers = (q: PageQuery = {}) => paginate(inventoryTransfers, q.page, q.size, (x) => kwHit(q.keyword, x.transferNo, x.fromLocation, x.toLocation));
export const listOtaRollouts = (q: PageQuery = {}) => paginate(otaRollouts, q.page, q.size, (x) => kwHit(q.keyword, x.rolloutNo, x.fwVersion, x.vendorCode));

export const savePowerbank = (x: Partial<Powerbank>) => upsert(powerbanks, x, "powerbankNo", () => nextNo("PB", powerbanks));
export const saveInventoryTransfer = (x: Partial<InventoryTransfer>) => upsert(inventoryTransfers, x, "transferNo", () => nextNo("TR", inventoryTransfers));
export const saveOtaRollout = (x: Partial<OtaRollout>) => upsert(otaRollouts, x, "rolloutNo", () => nextNo("OTA", otaRollouts));

/** 设备日志查询：关键词(日志号/机柜/事件) + stream 双流筛选 + 日期范围(YYYY-MM-DD)。 */
export const listDeviceLogs = (q: PageQuery & { stream?: string; from?: string; to?: string } = {}) =>
  paginate(deviceLogs, q.page, q.size, (x) => {
    if (!kwHit(q.keyword, x.logNo, x.cabinetNo, x.eventType, x.vendorCode)) return false;
    if (q.stream && x.stream !== q.stream) return false;
    const day = x.occurredAt.slice(0, 10);
    if (q.from && day < q.from) return false;
    if (q.to && day > q.to) return false;
    return true;
  });

export const listDeviceCodeBatches = (q: PageQuery = {}) =>
  paginate(deviceCodeBatches, q.page, q.size, (x) => kwHit(q.keyword, x.batchNo, x.vendorCode, x.rangeStart, x.rangeEnd));
export const saveDeviceCodeBatch = (x: Partial<DeviceCodeBatch>) =>
  upsert(deviceCodeBatches, x, "batchNo", () => nextNo("BC", deviceCodeBatches));

// —— G1 软删除：机柜 / 充电宝 ——
export const archiveCabinet = (no: string) => archiveRow(cabinets, "cabinetNo", no);
export const unarchiveCabinet = (no: string) => unarchiveRow(cabinets, "cabinetNo", no);
export const archivePowerbank = (no: string) => archiveRow(powerbanks, "powerbankNo", no);
export const unarchivePowerbank = (no: string) => unarchiveRow(powerbanks, "powerbankNo", no);

/**
 * G2 导入：机柜台账批量落库（唯一的导入口）。
 *
 * **先全量校验、再整批落库**——不允许「导一半失败」留下半截数据，
 * 故本函数只接收调用方已校验过的行，自身仍做一次机柜号查重兜底：
 * 已存在的机柜号一律走更新（幂等重导），不存在的新增。
 */
export function importCabinets(rows: Partial<Cabinet>[]): { imported: number; updated: number } {
  let imported = 0, updated = 0;
  for (const r of rows) {
    const i = cabinets.findIndex((c) => c.cabinetNo === r.cabinetNo);
    if (i >= 0) { cabinets[i] = { ...cabinets[i], ...r }; updated++; }
    else { cabinets.unshift({ ...DEFAULT_CABINET, ...r } as Cabinet); imported++; }
  }
  return { imported, updated };
}
const DEFAULT_CABINET: Cabinet = {
  // 导入的新机柜默认平台直营（agentNo=null）：归属只能经代理域的划拨动作落，不从 CSV 里塞
  cabinetNo: "", sn: "", vendorCode: "cd-tech", model: "X6", locationNo: null, locationName: null, agentNo: null,
  slotTotal: 8, availableCount: 0, onlineStatus: "OFFLINE", status: "DEPLOYED",
  fwVersion: "1.0.0", lastHeartbeatAt: null, archivedAt: null,
};
