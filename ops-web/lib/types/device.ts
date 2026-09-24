// 覆盖范围：设备域（ops/gw）——机柜、仓位、充电宝、在线监控、远程指令、
// 库存调拨、OTA 升级、设备日志、设备编码批次。

// —— 设备（ops 域）——
import type { Archivable } from "./common";

export type OnlineStatus = "ONLINE" | "OFFLINE";
/**
 * 机柜状态。
 *
 * ⚠️ `IN_STOCK`（在库未投放）此前**漏在这里** —— 而后端新建机柜默认就是它
 * （CabinetServiceImpl：「还没上架就置 ONLINE 会让它出现在 C 端可借列表里」），
 * 「库存调拨」整个功能管的也正是这批柜子（IN_STOCK ↔ DEPLOYED 来回流转）。
 * 结果是仓库里的机柜在运营端**全是未知状态**：徽标映射不上、按状态筛不出来，
 * 而两边都不报错。（后端 StatusVocabularyAcrossEndsTest 现在盯着这类不一致。）
 */
export type CabinetStatus = "IN_STOCK" | "DEPLOYED" | "FAULT" | "RETIRED";
export interface Cabinet extends Archivable {
  cabinetNo: string;
  sn: string;
  vendorCode: string;
  model: string;
  locationNo: string | null;
  locationName?: string | null;
  /**
   * 归属站点（`sites.siteNo`），空 = 到货未上架（没有点位就没有站点）。台账偏差 A1 的剩余项：
   * 原先前端只有点位号，「这台柜子在哪个站点」得靠点位反查，跨页深链（站点坪效/门店生命周期）
   * 无从下手。**不独立维护**：值恒等于 `locationNo` 所在点位的站点，由点位反查得出，
   * 避免同一台机柜的点位与站点互相矛盾。
   *
   * 2026-09-23 更新：这里原先写「DDL 还没有 site_no 列」，**已经过时** ——
   * V9（数据范围锚点）就补上了该列，且机柜建档 `saveCabinet` 现在按点位反查回填
   * （三者各填各的必然互相矛盾，所以后端不接受前端直接指定 siteNo）。
   * 仍可能为空：机柜到货未上架时本就没有点位，因而没有站点。
   */
  siteNo: string | null;
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
/**
 * 远程指令词表：**能下发的与记录里能表达的必须是同一套**。
 * 原先三处各说各话——详情页发 `EJECT_ANY`/`EJECT_SLOT`、批量发 `FW_SYNC`，
 * 而 `CommandRecord.type` 只有四个值，于是指令记录根本放不下这些指令（真接后端时
 * 记录列表会显示空白类型）。收敛口径：弹仓一律 `EJECT`，带 `slotIndex` = 指定仓、
 * 不带 = 任意仓；`FW_SYNC` 进词表。后端 `sendCommand` 收的是自由字符串，不受影响。
 */
export const COMMAND_TYPES = ["EJECT", "LOCK", "REBOOT", "LOCATE", "FW_SYNC"] as const;
export type CommandType = (typeof COMMAND_TYPES)[number];
/**
 * 必须指定仓位的指令：锁仓说不清「锁哪个仓」就是废指令。
 * 弹仓刻意不在此列——不带仓位 = 任意仓（借还主流程走的就是这条）。
 */
export const SLOT_REQUIRED_COMMANDS: readonly CommandType[] = ["LOCK"];

export interface CommandRecord {
  commandId: string;
  /**
   * 设备硬件序列号。与 {@link cabinetNo}（业务编号）不是一回事 ——
   * 跟厂商对日志时对方只认 sn，只有柜机号就得先来回查一次映射。
   */
  sn: string | null;
  cabinetNo: string;
  type: CommandType;
  slotIndex: number | null;
  status: "SENT" | "ACKED" | "TIMEOUT" | "FAILED";
  /**
   * 下发重试次数。**一次成功和重试三次才成功是两种设备状态**，
   * 而两者的 status 都是 ACKED —— 只看状态永远看不出后者。
   */
  retry: number | null;
  operator: string;
  /** 触发这条指令的订单（借出/归还）。运维手动下发的为 null。 */
  orderNo: string | null;
  /**
   * 下发时刻 / 设备确认时刻。**只有 createdAt 时，「设备多久才认」这个排障里
   * 第一个要问的数答不出来** —— 落库就有这两列，出参也一直在给。
   */
  sentAt: string | null;
  confirmedAt: string | null;
  createdAt: string;
}
export interface InventoryTransfer {
  transferNo: string;
  /**
   * 调出 / 调入的**类型 + 引用**（如 `SITE`+`ST300`、`WAREHOUSE`+`WH01`）。
   *
   * `fromLocation`/`toLocation` 是拼给人看的名字。只有名字时，
   * 调拨单看不出东西到底去了哪个站点/仓库，也没法从这里跳过去。
   */
  fromType: string | null;
  fromRef: string | null;
  toType: string | null;
  toRef: string | null;
  /** 调拨物类型（充电宝 / 机柜…）：不同物类的盘点口径不同。 */
  itemType: string | null;
  fromLocation: string;
  toLocation: string;
  powerbankCount: number;
  status: "DRAFT" | "IN_TRANSIT" | "DONE";
  operator: string;
  createdAt: string;
}
/**
 * 调拨单里的一台设备。
 *
 * 列表只说得出「从哪到哪、多少台」；**盘点对不上时要查的是「具体哪几台」**，
 * 那就只能看这一层。`checked` = 收货时是否已逐台核对过。
 */
export interface TransferItem {
  transferNo: string;
  /** 设备业务号（充电宝 PB* / 机柜 CAB*），按 `itemType` 决定是哪一类。 */
  itemNo: string;
  checked: boolean;
}

/** 调拨单详情 = 单据 + 明细行。后端 `GET /api/ops/inventory-transfers/{transferNo}`。 */
export interface InventoryTransferDetail {
  transfer: InventoryTransfer;
  items: TransferItem[];
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

/**
 * 固件版本库（`dev_ota_release`）：投放引用的「货架」。
 * 原先前端只有投放（OtaRollout）没有版本库，于是「投的是哪个包、校验和是多少、是否强制升级」
 * 全都无处可看——投放页填的固件版本号只是一个自由文本。
 */
export interface OtaRelease {
  releaseNo: string;
  /**
   * 固件类型（主控 / 仓门 / 通信模组…）。**刻意不收成联合类型**：后端 DDL 是自由文本、
   * 厂商随时会上报新类型，收紧只会让未知值在页面显示成空白（同 DeviceLog.eventType 的处理）。
   */
  fwType: string;
  /** 适用供应商；null = 通用固件（后端「空表示通用」），因此各厂商的投放都能引用同一个版本。 */
  vendorCode: string | null;
  /** 固件版本号（如 1.4.2），投放的 `fwVersion` 必须取自这里。 */
  version: string;
  /** 版本序号：比大小判断能否升级，也是版本库的排序键（后端按它倒序）。 */
  versionCode: number;
  artifactUrl: string;
  checksum: string;
  mandatory: boolean;
  status: "DRAFT" | "PUBLISHED" | "PAUSED" | "COMPLETED";
  releaseNotes: string;
}

/**
 * OTA 逐设备任务（`dev_ota_task`）：一次投放 1─* 任务。
 * 投放行上那个百分比是这批任务的均值，看不到逐台明细时「卡在 60% 不动」无法定位到具体哪台柜子。
 */
export interface OtaTask {
  taskNo: string;
  rolloutNo: string;
  cabinetNo: string;
  status: "PENDING" | "DOWNLOADING" | "DOWNLOADED" | "INSTALLING" | "SUCCESS" | "FAILED" | "ROLLED_BACK";
  progress: number; // 单机进度 0..100
  /** 升级前版本，回滚依据。 */
  previousVersion: string;
  /** 失败原因；仅 FAILED 有值。 */
  error: string | null;
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
