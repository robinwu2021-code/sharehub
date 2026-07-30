// 覆盖范围：设备域（ops/gw）——机柜、仓位、充电宝、在线监控、远程指令、
// 库存调拨、OTA 升级、设备日志、设备编码批次。

// —— 设备（ops 域）——
import type { Archivable } from "./common";

export type OnlineStatus = "ONLINE" | "OFFLINE";
export type CabinetStatus = "DEPLOYED" | "FAULT" | "RETIRED";
export interface Cabinet extends Archivable {
  cabinetNo: string;
  sn: string;
  vendorCode: string;
  model: string;
  locationNo: string | null;
  locationName?: string | null;
  /**
   * 归属代理（`agents.agentNo`），空 = 平台直营。台账偏差 A1：后端 `DevCabinet` 有此列、
   * 前端原先没有，导致「这台柜子归谁」在前端拿不到、划拨也无从落地。
   * **唯一写入口是代理域的划拨/回收**（`assignAgentAssets` / `reclaimAgentAssets`）。
   */
  agentNo: string | null;
  slotTotal: number;
  availableCount: number; // 可借（在仓充电宝数）
  onlineStatus: OnlineStatus;
  status: CabinetStatus;
  fwVersion: string;
  lastHeartbeatAt: string | null;
}

export type SlotLock = "LOCKED" | "UNLOCKED";
export interface Slot {
  slotIndex: number;
  powerbankNo: string | null;
  battery: number | null;
  lockStatus: SlotLock;
  health: "OK" | "FAULT";
}

// 注：曾有一个未被使用的 PowerbankStatus（IN_STOCK/DEPLOYED/IN_USE/RETURNED/SCRAP/LOST），
// 与实际在用的 Powerbank.status（IN_CABINET/RENTED/FAULT/RETIRED）是两套词表，已删（台账 T1）。
// ⚠️ 后端建表时词表要重新定：真实业务的「在库/已投放/在租/已归还/报废/丢失」比现在的 4 值更完整。

// —— 设备 · 待建功能补全（ops/gw 域）——
export interface Powerbank extends Archivable {
  powerbankNo: string;
  cabinetNo: string;
  battery: number; // 0..100
  status: "IN_CABINET" | "RENTED" | "FAULT" | "RETIRED";
  health: "OK" | "FAULT";
  cycles: number;
}
export interface CabinetMonitor {
  cabinetNo: string;
  locationName: string;
  online: boolean;
  heartbeatAt: string;
  signal: number; // 0..100
  temp: number;
  faultCount: number;
}
export interface CommandRecord {
  commandId: string;
  cabinetNo: string;
  type: "EJECT" | "LOCK" | "REBOOT" | "LOCATE";
  slotIndex: number | null;
  status: "SENT" | "ACKED" | "TIMEOUT" | "FAILED";
  operator: string;
  createdAt: string;
}
export interface InventoryTransfer {
  transferNo: string;
  fromLocation: string;
  toLocation: string;
  powerbankCount: number;
  status: "DRAFT" | "IN_TRANSIT" | "DONE";
  operator: string;
  createdAt: string;
}
export interface OtaRollout {
  rolloutNo: string;
  fwVersion: string;
  vendorCode: string;
  strategy: "GRAY" | "FULL";
  progress: number; // 0..100
  status: "PENDING" | "RUNNING" | "DONE" | "ROLLBACK";
  createdAt: string;
}

// —— 设备日志（阶段 2）——
// 对标竞品「充电桩日志」（只有设备上报）。我们做**双流合一**：指令下发(COMMAND/DOWN)
// 与设备上报(REPORT/UP) 在同一时间轴，排障时因果可见。
export interface DeviceLog {
  logNo: string;
  cabinetNo: string;
  stream: "COMMAND" | "REPORT"; // 双流标识：下发 / 上报
  direction: "DOWN" | "UP"; // 方向，与 stream 对应
  eventType: string; // EJECT / HEARTBEAT / SLOT_STATE / FW_UPGRADE ...
  payload: string; // 报文（JSON 字符串；列表行内截断，展开看全文）
  vendorCode: string;
  occurredAt: string;
  result: "OK" | "TIMEOUT" | "FAILED";
}

// —— 设备编码（阶段 2）——
// 对标竞品「充电桩编码」（平铺列表）。我们按**批次 + 供应商**归集，并跟踪绑定进度。
export interface DeviceCodeBatch {
  batchNo: string;
  vendorCode: string;
  codeType: "QR" | "SN"; // 二维码 / 出厂序列号
  rangeStart: string;
  rangeEnd: string;
  total: number;
  bound: number; // 已绑定数（列表显示 已绑定/总数 + 进度条）
  producedAt: string;
  status: "PENDING" | "PARTIAL" | "BOUND" | "VOID";
}
