// 设备域：机柜 / 仓位 / 充电宝 / 实时监控 / 远程指令 / 调拨 / OTA / 供应商接入 / 设备日志 / 设备编码批次。
// cabinets 是全库的“机柜号来源”，其他域（订单/告警/客服/营销广告位…）一律通过 cabNo() 引用，不复制数据。
import type {
  Cabinet, Slot, Vendor, Powerbank, CabinetMonitor, CommandRecord, CommandType,
  InventoryTransfer, InventoryTransferDetail, TransferItem, InvTransferStatus, TransferAction, InvTransferReq,
  OtaRollout, OtaRelease, OtaTask, DeviceLog, DeviceCodeBatch, PageQuery,
} from "../../types";
// 指令词表是 types 层 SSOT：抽屉里的选项、这里的落库校验同源，避免「界面能选、落库不认」
import { COMMAND_TYPES, SLOT_REQUIRED_COMMANDS, TRANSFER_TRANSITIONS, canTransferAction } from "../../types";
import { VENDORS, LOCS, OPERATORS, p, iso } from "./internal";
import { paginate, kwHit, upsert, nextNo, liveHit, archiveRow, unarchiveRow } from "./helpers";
import { fail, notFound } from "@/lib/biz-error";
// 机柜归属站点（A1）取自场所域的真实点位，**不另造字符串**。依赖方向 device → location
// 是单向的（location.ts 不引用设备），与 agent.ts 反过来引用 cabinets 的做法不冲突。
import { locations } from "./location";

// —— 设备 ——
/**
 * 点位 → 站点反查：机柜的 `siteNo` 恒等于它所在点位的站点，不独立维护。
 * 点位为空（到货未上架）或点位号查不到时返回 null，绝不编一个站点号出来。
 */
const locOf = (locationNo: string | null | undefined) =>
  locations.find((l) => l.locationNo === locationNo) ?? null;
export const siteNoOfLocation = (locationNo: string | null | undefined) => locOf(locationNo)?.siteNo ?? null;

/**
 * 点位 → 台账上的「归属投影」：站点号 + 站点名一起反查。
 * `Cabinet.locationName` 存的就是**站点名**（引用完整性盯着 `sites.name`），所以它和
 * `siteNo` 一样不能由表单/CSV 给——两者分别来源就会出现「站点号是 A、站点名是 B」。
 */
export const cabinetPlacement = (locationNo: string | null | undefined) => {
  const loc = locOf(locationNo);
  return { siteNo: loc?.siteNo ?? null, locationName: loc?.siteName ?? null };
};

export const cabinets: Cabinet[] = Array.from({ length: 48 }, (_, i) => {
  const total = p([6, 8, 12], i);
  const online = i % 9 !== 0;
  const locationNo = `LOC${200 + (i % LOCS.length)}`;
  return {
    cabinetNo: `CAB${1000 + i}`, sn: `SN${90000 + i}`, vendorCode: p(VENDORS, i),
    // 归属代理（A1）：号段与 agents（AG001–AG009）对齐；每 5 台留 1 台平台直营，
    // 划拨抽屉才有「未归属」的候选可选。这里刻意不 import agent.ts —— 保持
    // device → agent 的依赖方向单向（agent.ts 反过来引用 cabinets 反算设备数）。
    agentNo: i % 5 === 0 ? null : `AG${String((i % 9) + 1).padStart(3, "0")}`,
    model: p(["X6", "S8", "M12"], i), locationNo,
    // 站点由点位反查：与 locationName（存的就是站点名）天然对得上，不会出现「点位在 A 站、站点写 B」
    siteNo: siteNoOfLocation(locationNo),
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
  // 七档照 DDL 铺开（原来这里有 RETIRED —— 后端词表里没有这个值）。
  const st = p(["IN_CABINET", "IN_CABINET", "RENTED", "FAULT", "IN_STOCK", "LOST", "SOLD", "SCRAP"] as const, i);
  const cab = p(cabinets, i);
  return {
    powerbankNo: `PB${20000 + i}`,
    sn: `PBSN${70000 + i}`,
    // 充电宝的厂商**跟所在机柜同源**：现实里是整柜配套采购的，
    // 各自随机取一个厂商会让「某厂商故障集中」这类排查看到假信号。
    vendorCode: cab.vendorCode,
    cabinetNo: cab.cabinetNo,
    // 借出中的不在任何柜子里 → 仓位为 null。给它编一个仓位号等于谎称它还在柜子上。
    slotIndex: st === "RENTED" ? null : (i % 8) + 1,
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
  const status = p(["ACKED", "ACKED", "SENT", "TIMEOUT", "FAILED"] as const, i);
  const createdAt = iso(i * 900_000);
  /*
   * 排障四件套的种子值要**彼此自洽**，否则新列看着有数、实际互相矛盾：
   *   · 只有 ACKED 才有 confirmedAt（设备认了才叫确认）；
   *   · TIMEOUT/FAILED 的 retry 必然 >1（重试完才判定失败），ACKED 多为 1；
   *   · SENT 还在途：发了、没确认；
   *   · EJECT 是订单驱动（借出/归还），REBOOT/LOCATE 是运维手动下发 → orderNo 为 null。
   */
  const acked = status === "ACKED";
  const failedish = status === "TIMEOUT" || status === "FAILED";
  return {
    commandId: `CMD${880000 + i}`,
    // 与同一台机柜的 sn 对上 —— 跟厂商对日志时对方只认 sn
    sn: p(cabinets, i).sn,
    cabinetNo: cabNo(i), type,
    slotIndex: type === "EJECT" || type === "LOCK" ? (i % 8) + 1 : null,
    status,
    retry: failedish ? 2 + (i % 3) : 1,
    operator: p(OPERATORS, i),
    orderNo: type === "EJECT" ? `ORD${20260900 + i}` : null,
    // 下发比创建晚几秒（排队），确认再晚几秒（设备往返）
    sentAt: iso(i * 900_000 - 3_000),
    confirmedAt: acked ? iso(i * 900_000 - 3_000 - (2_000 + (i % 7) * 1_500)) : null,
    createdAt,
  };
});
/**
 * 调拨明细：**逐台**列出这一单调了哪些设备，以及收货时有没有逐台核对过。
 *
 * 条数必须等于单据上的 `powerbankCount` —— 两个数对不上，
 * 盘点时就会变成「单据说 12 台、明细只有 9 行」这种没人说得清的差异。
 * 已完成（DONE）的单视为全部核对过；在途/草稿只核对了一部分。
 */
/**
 * 明细的落库处。种子单据的明细按单号推算（见下），**第一次读到时落进这里**，
 * 之后的设定明细 / 签收核对都改这份 —— 否则每次读都重算，签收勾过的件刷新后又变回未核对。
 */
export const transferItemStore = new Map<string, TransferItem[]>();

export const transferItemsOf = (transferNo: string): TransferItem[] => {
  const stored = transferItemStore.get(transferNo);
  if (stored) return stored;
  const t = inventoryTransfers.find((x) => x.transferNo === transferNo);
  if (!t) return [];
  // 新建的单（种子之外）一开始没有明细：明细要经「设定明细」逐件录入，不凭台数编
  if (!SEED_TRANSFER_NOS.has(transferNo)) {
    transferItemStore.set(transferNo, []);
    return transferItemStore.get(transferNo)!;
  }
  const base = Number(transferNo.replace(/\D/g, "")) || 0;
  const seeded = Array.from({ length: t.powerbankCount }, (_, k) => ({
    transferNo,
    // 余数不能只在 30 以内取——单据最多 44 台，那样同一单里会出现两行同号
    itemNo: `PB${20000 + (base * 97 + k) % 900}`,
    checked: t.status === "DONE" || k < Math.floor(t.powerbankCount / 2),
  }));
  transferItemStore.set(transferNo, seeded);
  return seeded;
};

export const getInventoryTransfer = (transferNo: string): InventoryTransferDetail => {
  const transfer = inventoryTransfers.find((x) => x.transferNo === transferNo);
  if (!transfer) notFound("调拨单", "Transfer", transferNo);
  return { transfer: transfer!, items: transferItemsOf(transferNo) };
};

export const inventoryTransfers: InventoryTransfer[] = Array.from({ length: 16 }, (_, i) => ({
  transferNo: `TR${60000 + i}`,
  // 类型 + 引用：名字是给人看的，跳转与盘点要靠编号。
  // 一半从仓库出、一半站点之间调，两种形态都要有样本。
  fromType: i % 2 === 0 ? "WAREHOUSE" : "SITE",
  fromRef: i % 2 === 0 ? `WH0${1 + (i % 2)}` : `ST${300 + (i % 12)}`,
  toType: "SITE", toRef: `ST${300 + ((i + 2) % 12)}`,
  itemType: "POWERBANK",
  fromLocation: p(LOCS, i), toLocation: p(LOCS, i + 2),
  powerbankCount: 5 + (i * 3) % 40, status: p(["DRAFT", "IN_TRANSIT", "DONE"] as const, i),
  operator: p(OPERATORS, i), createdAt: iso(i * 43200_000),
}));
/** 种子单据号：只有它们的明细按台数推算，新建的单明细从空开始。 */
const SEED_TRANSFER_NOS = new Set(inventoryTransfers.map((t) => t.transferNo));
// —— 固件 OTA：版本库（货架）→ 投放（灰度/全量）→ 逐设备任务（下钻）——
// 三层共用一份种子，**投放不再自带 progress**：投放的百分比由它的任务均值算出，
// 固件版本只能取版本库里已存在的版本。否则「列表 62% / 点开抽屉 4 台全 100%」这类
// 自相矛盾的假数据会让人以为页面坏了（版本库缺 1.4.1 时投放行也会点进去查无此版本）。

/** 版本库按 versionCode 倒序排列，与后端 `releases()` 的排序一致（mock 无排序能力，靠声明顺序）。 */
export const otaReleases: OtaRelease[] = [
  { releaseNo: "FW908", fwType: "MODEM", vendorCode: "cd-tech", version: "4.1.0", versionCode: 410,
    artifactUrl: "https://fw.example/pb/modem-4.1.0.bin", checksum: "sha256:9f21c4ab7e", mandatory: false,
    status: "DRAFT", releaseNotes: "通信模组：弱网重连退避策略调整，尚未灰度" },
  { releaseNo: "FW907", fwType: "SLOT", vendorCode: "sd-power", version: "3.2.0", versionCode: 320,
    artifactUrl: "https://fw.example/pb/slot-3.2.0.bin", checksum: "sha256:41ba0d9c72", mandatory: false,
    status: "PUBLISHED", releaseNotes: "仓门：弹出电机堵转检测阈值下调，减少卡仓" },
  { releaseNo: "FW906", fwType: "MCU", vendorCode: null, version: "2.1.0", versionCode: 210,
    artifactUrl: "https://fw.example/pb/mcu-2.1.0.bin", checksum: "sha256:c07e5518af", mandatory: false,
    status: "DRAFT", releaseNotes: "主控：2.0.0 回滚问题的修复版，待验证后发布" },
  { releaseNo: "FW905", fwType: "MCU", vendorCode: null, version: "2.0.0", versionCode: 200,
    artifactUrl: "https://fw.example/pb/mcu-2.0.0.bin", checksum: "sha256:6d3af1029b", mandatory: false,
    status: "PAUSED", releaseNotes: "主控大版本：现场自检失败已整批回滚，暂停继续投放" },
  { releaseNo: "FW904", fwType: "MCU", vendorCode: null, version: "1.5.0", versionCode: 150,
    artifactUrl: "https://fw.example/pb/mcu-1.5.0.bin", checksum: "sha256:2e88b4fd15", mandatory: true,
    status: "PUBLISHED", releaseNotes: "主控：修复归还检测偶发漏判（安全修复，强制升级）" },
  { releaseNo: "FW903", fwType: "MCU", vendorCode: null, version: "1.4.1", versionCode: 141,
    artifactUrl: "https://fw.example/pb/mcu-1.4.1.bin", checksum: "sha256:70cc9e3a68", mandatory: false,
    status: "PUBLISHED", releaseNotes: "主控：心跳上报间隔可配置" },
  { releaseNo: "FW902", fwType: "MCU", vendorCode: null, version: "1.4.0", versionCode: 140,
    artifactUrl: "https://fw.example/pb/mcu-1.4.0.bin", checksum: "sha256:1ab4402fd9", mandatory: false,
    status: "PUBLISHED", releaseNotes: "主控：低电量阈值上报 + 仓位状态全量补报" },
  { releaseNo: "FW901", fwType: "MCU", vendorCode: null, version: "1.3.1", versionCode: 131,
    artifactUrl: "https://fw.example/pb/mcu-1.3.1.bin", checksum: "sha256:88f0e1c4a7", mandatory: false,
    status: "COMPLETED", releaseNotes: "主控：老版本，仍有存量机柜在跑" },
  { releaseNo: "FW900", fwType: "MCU", vendorCode: null, version: "1.2.0", versionCode: 120,
    artifactUrl: "https://fw.example/pb/mcu-1.2.0.bin", checksum: "sha256:5c19aa7b30", mandatory: false,
    status: "COMPLETED", releaseNotes: "主控：出厂版本" },
];

/** 投放种子：progress 缺席（由任务算），fwVersion 取自 otaReleases 里已发布/已暂停的主控版本。 */
const ROLLOUT_SEEDS = Array.from({ length: 14 }, (_, i) => {
  const fwVersion = p(["1.4.0", "1.4.1", "1.5.0", "2.0.0"], i);
  /*
   * 投放必然引用版本库里的一条（`dev_ota_rollout.release_no` 是 NOT NULL）。
   * **厂商也跟着这条走**，不再各自随机取 —— 否则种子里会出现
   * 「cd-tech 的投放发了 sd-power 的固件包」，而这种矛盾在页面上看不出来。
   */
  const rel = otaReleases.find((r) => r.version === fwVersion)!;
  const strategy = (i % 3 === 0 ? "FULL" : "GRAY") as OtaRollout["strategy"];
  /*
   * 范围与策略要自洽：全量（FULL）必然是 ALL；灰度（GRAY）才谈得上
   * 只发某个站点或某台设备。反过来配（FULL + 单台）在业务上讲不通。
   */
  const scope: OtaRollout["scope"] = strategy === "FULL" ? "ALL" : (i % 2 === 0 ? "LOCATION" : "DEVICE");
  return {
    rolloutNo: `OTA${5000 + i}`,
    releaseNo: rel.releaseNo,
    fwVersion,
    // OtaRelease.vendorCode 类型上可空、OtaRollout.vendorCode 不可空（前端这两处
    // 本身不一致）。mock 的版本库条条都有厂商，兜底只为类型成立，不是真会走到。
    vendorCode: rel.vendorCode ?? p(VENDORS, i),
    strategy,
    scope,
    // 站点号沿用 location.ts 的 `ST${300 + i}` 形态。**不 import 那个模块** ——
    // device 不依赖 location，引进来有循环依赖风险。改了那边的编号规则记得同步。
    targetRef: scope === "ALL" ? null : scope === "LOCATION" ? `LOC${200 + (i % 5)}` : cabNo(i),
    status: p(["PENDING", "RUNNING", "DONE", "ROLLBACK"] as const, i),
    createdAt: iso(i * 86400_000),
  };
});

const codeOf = (version: string) => otaReleases.find((r) => r.version === version)?.versionCode ?? 0;
/** 升级前版本只能取「版本库里比目标低的档位」——出现 1.5.0 → 1.5.0 这种就不是升级了。 */
const prevOf = (target: string, k: number) => {
  const lower = otaReleases.filter((r) => r.versionCode < codeOf(target)).map((r) => r.version);
  return lower.length ? p(lower, k) : "1.0.0";
};

/** RUNNING 投放的逐台进度分布：先成功一台、再一台装、一台下载，其余排队（灰度该有的样子）。 */
const RUNNING_STEPS = [
  { status: "SUCCESS", progress: 100 }, { status: "INSTALLING", progress: 70 },
  { status: "DOWNLOADING", progress: 35 }, { status: "DOWNLOADED", progress: 50 },
  { status: "PENDING", progress: 0 },
] as const;

export const otaTasks: OtaTask[] = ROLLOUT_SEEDS.flatMap((s, i) =>
  Array.from({ length: 3 + (i % 3) }, (_, j): OtaTask => {
    const base = {
      taskNo: `OTK${7000 + i * 10 + j}`, rolloutNo: s.rolloutNo,
      cabinetNo: cabNo(i * 5 + j), previousVersion: prevOf(s.fwVersion, i + j),
    };
    if (s.status === "PENDING") return { ...base, status: "PENDING", progress: 0, error: null };
    if (s.status === "DONE") return { ...base, status: "SUCCESS", progress: 100, error: null };
    // 回滚是「一台炸了整批退回」，故第一台留 FAILED + 原因，其余 ROLLED_BACK——
    // 不留那台失败任务，运营就永远查不到当初为什么回滚
    if (s.status === "ROLLBACK") {
      return j === 0
        ? { ...base, status: "FAILED", progress: 40, error: "安装后自检失败：仓门电机无响应" }
        : { ...base, status: "ROLLED_BACK", progress: 0, error: null };
    }
    return { ...base, ...RUNNING_STEPS[j % RUNNING_STEPS.length], error: null };
  }),
);

export const otaRollouts: OtaRollout[] = ROLLOUT_SEEDS.map((s) => {
  const mine = otaTasks.filter((t) => t.rolloutNo === s.rolloutNo);
  return { ...s, progress: Math.round(mine.reduce((n, t) => n + t.progress, 0) / mine.length) };
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

/**
 * 充电宝建档 / 改属性（同后端 PowerbankServiceImpl）：**建档一律在库**，不接受调用方指定状态
 * （否则可绕过状态机凭空造出一块「借出中」的宝）；编辑不改状态 —— 状态只经 `transitPowerbank`。
 */
export const savePowerbank = (x: Partial<Powerbank>) => {
  const { status: _ignored, ...attrs } = x;
  void _ignored;
  const exists = !!x.powerbankNo && powerbanks.some((p) => p.powerbankNo === x.powerbankNo);
  // SN 非空（后端 dev_powerbank.sn NOT NULL，不填建档直接失败）
  if (!exists && !String(x.sn ?? "").trim()) fail("硬件序列号 SN 必填", "SN is required", "الرقم التسلسلي مطلوب");
  return upsert(powerbanks, exists ? attrs : {
    sn: null, vendorCode: null, slotIndex: null, battery: 100, cycles: 0, health: "OK", archivedAt: null,
    ...attrs, cabinetNo: attrs.cabinetNo ?? "", status: "IN_STOCK",
  }, "powerbankNo", () => nextNo("PB", powerbanks));
};
/**
 * 新增 / 编辑调拨单。
 *
 * 此前是裸 `upsert`，于是 mock 下有两件真后端不允许的事：
 * ① 新建时可以直接开一张「在途」甚至「已完成」的单（后端 `body.setStatus(DRAFT)` 强制）；
 * ② 状态可以 DRAFT 直接跳 DONE（后端 `InvTransferStateMachine` 按非法迁移拒）。
 * 两边行为不一致正是 mock 该消除的分叉 —— 页面在 mock 下看着是通的，切后端当场崩。
 *
 * `requireAllChecked`（收货前明细必须全核对）**不在这里** —— 那是前置条件不是状态机的边，
 * 且 mock 的明细核对状态由另一条路径维护，硬塞会让两个概念混在一句报错里。
 */
export function saveInventoryTransfer(
  x: Partial<Omit<InventoryTransfer, "powerbankCount">> & Partial<InvTransferReq>,
): InventoryTransfer {
  const existing = x.transferNo
    ? inventoryTransfers.find((t) => t.transferNo === x.transferNo)
    : undefined;
  // 写入面叫 fromName / toName（后端 InvTransferReq），落到出参的 fromLocation / toLocation
  const { fromName, toName, powerbankCount, ...rest } = x;
  const head: Partial<InventoryTransfer> = {
    ...rest,
    ...(powerbankCount != null ? { powerbankCount } : {}),
    ...(fromName ? { fromLocation: fromName } : {}),
    ...(toName ? { toLocation: toName } : {}),
  };

  if (!existing) {
    const draft = { fromLocation: "", toLocation: "", powerbankCount: 0, operator: "admin", ...head };
    validateEndpoints(draft);
    // 建单一律 DRAFT，不接受调用方直接开在途单（同后端）；经办人按当前登录人落，不收请求体
    return upsert(inventoryTransfers, { ...draft, status: "DRAFT", operator: "admin",
      createdAt: new Date().toISOString() }, "transferNo",
      () => nextNo("TR", inventoryTransfers));
  }
  if (existing.status === "DONE") {
    throw new TransferError(`调拨单已完成，不可再修改: ${existing.transferNo}（如需退回请开一张反向调拨单，保留两条痕）`);
  }
  if (existing.status === "DRAFT") validateEndpoints({ ...existing, ...head });

  const target = x.status;
  if (target && target !== existing.status) {
    const action = (Object.keys(TRANSFER_TRANSITIONS) as TransferAction[])
      .find((a) => TRANSFER_TRANSITIONS[a].to === target);
    if (!action) {
      throw new TransferError(`不支持的目标状态: ${target}`);
    }
    if (!canTransferAction(existing.status, action)) {
      throw new TransferError(
        `调拨单 ${existing.transferNo} 当前是「${TRANSFER_STATUS_LABEL[existing.status]}」，`
        + `不允许执行「${TRANSFER_TRANSITIONS[action].label}」`
        + `（允许自：${TRANSFER_TRANSITIONS[action].from.join(" / ")}）`);
    }
  }
  // 单头只有草稿期可改（同后端）：在途单改台数 / 两端等于事后编账，只接受状态迁移
  const nextStatus = head.status ?? existing.status;
  const patch = nextStatus === "DRAFT" ? head : { transferNo: existing.transferNo, status: nextStatus };
  return upsert(inventoryTransfers, patch, "transferNo", () => nextNo("TR", inventoryTransfers));
}

/**
 * 两端校验（同后端 `validateEndpoints`）。**只对带了类型的单据校验** ——
 * 种子与早期用例只有名字没有类型，那是历史形态，不因新规则整批判非法。
 */
function validateEndpoints(t: Partial<InventoryTransfer>) {
  if (t.fromType === undefined && t.toType === undefined && t.itemType === undefined) return;
  const TYPES = ["WAREHOUSE", "SITE", "LOCATION"];
  if (!TYPES.includes(t.fromType ?? "") || !TYPES.includes(t.toType ?? "")) {
    throw new TransferError("fromType/toType 必须是 WAREHOUSE/SITE/LOCATION 之一");
  }
  if (!t.fromRef?.trim() || !t.toRef?.trim()) throw new TransferError("fromRef/toRef 不能为空");
  if (t.fromType === t.toType && t.fromRef === t.toRef) throw new TransferError("调出方与调入方不能相同");
  if (!["CABINET", "POWERBANK"].includes(t.itemType ?? "")) throw new TransferError("itemType 必须是 CABINET/POWERBANK 之一");
}

/** 报错里用中文说状态，与页面徽标同一套说法。 */
const TRANSFER_STATUS_LABEL: Record<InvTransferStatus, string> = {
  DRAFT: "草稿", IN_TRANSIT: "在途", DONE: "已完成",
};

export class TransferError extends Error {
  constructor(msg: string) { super(msg); this.name = "TransferError"; }
}
export const saveOtaRollout = (x: Partial<OtaRollout>) => upsert(otaRollouts, x, "rolloutNo", () => nextNo("OTA", otaRollouts));

/** 版本库查询：关键词(版本号/发布单号/说明) + 固件类型 / 供应商 / 发布状态三筛。 */
export const listOtaReleases = (q: PageQuery & { fwType?: string; vendorCode?: string; status?: string } = {}) =>
  paginate(otaReleases, q.page, q.size, (x) => {
    if (!kwHit(q.keyword, x.releaseNo, x.version, x.releaseNotes)) return false;
    if (q.fwType && x.fwType !== q.fwType) return false;
    if (q.vendorCode && x.vendorCode !== q.vendorCode) return false;
    if (q.status && x.status !== q.status) return false;
    return true;
  });
export const saveOtaRelease = (x: Partial<OtaRelease>) => upsert(otaReleases, x, "releaseNo", () => nextNo("FW", otaReleases));

/** 某次投放的逐设备任务（不分页，抽屉里要看全量）。 */
export const listOtaTasks = (rolloutNo: string) => otaTasks.filter((t) => t.rolloutNo === rolloutNo);

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
    // 归属站点/站点名不从 CSV 里塞（模板也没这两列）：由点位反查，否则一台机柜会有互相矛盾的归属。
    // 点位留空即「到货未上架」，归属随之清空——CSV 的空值是明确表态，不是「保持原样」。
    const row = { ...r, ...cabinetPlacement(r.locationNo) };
    const i = cabinets.findIndex((c) => c.cabinetNo === row.cabinetNo);
    if (i >= 0) { cabinets[i] = { ...cabinets[i], ...row }; updated++; }
    else { cabinets.unshift({ ...DEFAULT_CABINET, ...row } as Cabinet); imported++; }
  }
  return { imported, updated };
}
// ————————————————————————————————————————————————————————————————
// 机柜建档 / 编辑（POST /api/ops/cabinets[/{cabinetNo}]）
// ⚠️ 后端缺口：写端点尚不存在（OpsController 只有 GET /cabinets 与 GET /cabinets/{no}）。
// 校验放这一层而不是抽屉里：导入、建档、将来的后端都得守同一套规则，
// 只写在表单里等于「换个入口就能塞脏数据」。
// ————————————————————————————————————————————————————————————————

/** 建档违规（编号/SN 重复、仓位数越界、点位不存在…）。 */
export class CabinetError extends Error {
  constructor(msg: string) { super(msg); this.name = "CabinetError"; }
}

/**
 * 机柜建档/编辑。**只接受建档字段**（SN/供应商/型号/仓位数/点位/固件/状态），
 * 其余字段一律不从入参取：
 * - `siteNo`/`locationName` 由点位反查（见 `cabinetPlacement`）；
 * - `agentNo` 只能经代理域的划拨写入；
 * - `onlineStatus`/`lastHeartbeatAt`/`availableCount` 是设备上报出来的事实，
 *   表单填一个「在线」不会让机器真的在线，只会让台账骗人。
 */
export function saveCabinet(x: Partial<Cabinet> & { cabinetNo?: string }): Cabinet {
  const no = (x.cabinetNo ?? "").trim();
  const idx = no ? cabinets.findIndex((c) => c.cabinetNo === no) : -1;
  const prev = idx >= 0 ? cabinets[idx] : null;
  // 手填柜机号才校验格式；留空走自动生成。号段与导入模板同一条规则（CAB + 数字）
  if (no && !prev && !/^CAB\d+$/.test(no)) throw new CabinetError("柜机号格式应为 CAB + 数字，如 CAB2000");

  const sn = String(x.sn ?? "").trim();
  if (sn.length < 4) throw new CabinetError("出厂序列号至少 4 位：SN 是现场核机与厂商保修的唯一凭据");
  const dup = cabinets.find((c, i) => i !== idx && c.sn === sn);
  if (dup) throw new CabinetError(`SN ${sn} 已被 ${dup.cabinetNo} 占用：同一台机器不能建两份档`);

  const model = String(x.model ?? "").trim();
  if (!model) throw new CabinetError("型号必填：仓位布局与配件都按型号走");
  if (!vendors.some((v) => v.vendorCode === x.vendorCode)) {
    throw new CabinetError(`未接入的供应商：${x.vendorCode ?? "(空)"}，可选 ${vendors.map((v) => v.vendorCode).join(" / ")}`);
  }

  const slotTotal = Number(x.slotTotal);
  if (!Number.isInteger(slotTotal) || slotTotal < 1 || slotTotal > 48) throw new CabinetError("仓位数应为 1~48 的整数");
  // 缩仓位不能把已在仓的充电宝挤出台账：可借数就是在仓宝数，缩到它以下台账立刻自相矛盾
  if (prev && slotTotal < prev.availableCount) {
    throw new CabinetError(`仓位数不得小于当前在仓充电宝数（${prev.availableCount}）：请先调拨出宝再缩仓`);
  }

  const locationNo = (x.locationNo ?? "").trim() || null;
  if (locationNo && !locations.some((l) => l.locationNo === locationNo)) {
    throw new CabinetError(`点位不存在：${locationNo}，请先在「渠道与场地 · 点位管理」建档`);
  }

  const row: Cabinet = {
    ...(prev ?? DEFAULT_CABINET),
    cabinetNo: prev?.cabinetNo ?? (no || nextNo("CAB", cabinets, 1000, "cabinetNo")),
    sn, vendorCode: x.vendorCode!, model, slotTotal, locationNo,
    // 状态只经动作改（R1，同后端「编辑不再改状态」）：新建一律在库，编辑保持原状态
    status: prev?.status ?? DEFAULT_CABINET.status,
    fwVersion: String(x.fwVersion ?? "").trim() || prev?.fwVersion || DEFAULT_CABINET.fwVersion,
    ...cabinetPlacement(locationNo),
  };
  if (prev) cabinets[idx] = row; else cabinets.unshift(row);
  return row;
}

// ————————————————————————————————————————————————————————————————
// 远程指令下发（POST /api/ops/cabinets/{cabinetNo}/commands，后端已实现）
// ————————————————————————————————————————————————————————————————

/** 指令违规（机柜不存在/已归档、未知指令、仓位越界）。 */
export class CommandError extends Error {
  constructor(msg: string) { super(msg); this.name = "CommandError"; }
}

/**
 * 下发一条远程指令，**并落一条指令记录**。
 * 原先 mock 只回一个假 commandId，于是「下发了 → 指令记录里查不到」，
 * 与真实链路（网关下发后必然留痕）不符，也让指令记录 tab 看起来是死数据。
 * 落库状态恒为 `SENT`：ACK 要等设备回，mock 里直接写「已确认」等于伪造设备回执。
 */
export function recordCommand(cabinetNo: string, type: string, params?: Record<string, unknown>): CommandRecord {
  const cab = cabinets.find((c) => c.cabinetNo === cabinetNo);
  if (!cab) throw new CommandError(`机柜不存在：${cabinetNo}`);
  if (cab.archivedAt) throw new CommandError(`机柜 ${cabinetNo} 已归档：先恢复再下发，否则指令发给一台账面上已不存在的机器`);
  if (!COMMAND_TYPES.includes(type as CommandType)) {
    throw new CommandError(`未知指令类型：${type}，可选 ${COMMAND_TYPES.join(" / ")}`);
  }
  const t = type as CommandType;
  const raw = params?.slotIndex;
  const slotIndex = raw === undefined || raw === null || raw === "" ? null : Number(raw);
  if (slotIndex === null && SLOT_REQUIRED_COMMANDS.includes(t)) throw new CommandError(`「${t}」必须指定仓位`);
  if (slotIndex !== null && (!Number.isInteger(slotIndex) || slotIndex < 1 || slotIndex > cab.slotTotal)) {
    throw new CommandError(`仓位号应在 1~${cab.slotTotal} 之间（${cabinetNo} 共 ${cab.slotTotal} 仓）`);
  }
  const operator = typeof params?.operator === "string" && params.operator.trim() ? params.operator.trim() : "admin";
  const now = new Date().toISOString();
  const rec: CommandRecord = {
    commandId: nextNo("CMD", commandRecords, 880000, "commandId"),
    sn: cab.sn,
    cabinetNo, type: t, slotIndex, status: "SENT", operator,
    // 刚下发：发了、设备还没认，重试 1 次。**confirmedAt 必须是 null 而不是 now** ——
    // 填上当前时间等于谎称设备秒确认，而这条指令可能永远不会被确认。
    retry: 1, orderNo: null, sentAt: now, confirmedAt: null,
    createdAt: now,
  };
  commandRecords.unshift(rec);
  return rec;
}

const DEFAULT_CABINET: Cabinet = {
  // 导入的新机柜默认平台直营（agentNo=null）：归属只能经代理域的划拨动作落，不从 CSV 里塞
  // 未上架的机柜没有点位、也就没有归属站点（siteNo 由点位反查，见 siteNoOfLocation）
  cabinetNo: "", sn: "", vendorCode: "cd-tech", model: "X6", locationNo: null, siteNo: null, locationName: null, agentNo: null,
  // 新建机柜默认在库（同后端 CabinetServiceImpl：还没上架就置在用会让它出现在 C 端可借列表里）
  slotTotal: 8, availableCount: 0, onlineStatus: "OFFLINE", status: "IN_STOCK",
  fwVersion: "1.0.0", lastHeartbeatAt: null, archivedAt: null,
};
