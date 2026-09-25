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
// IN_TRANSIT（运输中，2026-09-25 批次 C4）：随调拨单发出、签收后回 IN_STOCK，由调拨驱动、不单独出按钮
export type CabinetStatus = "IN_STOCK" | "IN_TRANSIT" | "DEPLOYED" | "FAULT" | "RETIRED";
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

/**
 * 充电宝生命周期。**七档，照 `dev_powerbank.status` 的 DDL 列注释与后端
 * `PowerbankStatus` 枚举、`PowerbankStateMachine` 逐个对过**。
 *
 * 此前这里是内联在 `Powerbank` 里的四值联合（IN_CABINET/RENTED/FAULT/**RETIRED**），
 * 而后端从来没有 RETIRED，也从来有 IN_STOCK/LOST/SOLD/SCRAP。后果两个方向都有：
 * 入库未投放、丢失待追偿、买断、报废这四类在运营端是**未知值**（徽标映射不上、
 * 按状态筛一条都查不到），而 RETIRED 是前端自造的，筛它永远是空。
 *
 * 这不是没人想到 —— 原来那条注释就写着「后端建表时词表要重新定，比现在的 4 值更完整」。
 * 后端定完了，没人回来改前端，而**没有任何东西会因此报红**：
 * 两端同名词表比对只认具名 `export type`，内联联合它一个都发现不了。具名就是为了进那个卡口。
 */
export type PowerbankStatus =
  | "IN_STOCK" | "IN_CABINET" | "RENTED" | "FAULT" | "LOST" | "SOLD" | "SCRAP";

/**
 * 充电宝健康（与后端 `PowerbankHealth` 同名同值）。
 *
 * `AGED` = 循环次数超过上限、**已停止借出、待回收报废**（后端每天定时标记，批次 D2）。
 * 此前前端只有 OK/FAULT：老化的宝在运营端是未知值，而它恰恰是「为什么这台柜子
 * 明明有宝却借不出」的答案之一。
 */
export type PowerbankHealth = "OK" | "FAULT" | "AGED";

// —— 设备 · 待建功能补全（ops/gw 域）——
export interface Powerbank extends Archivable {
  powerbankNo: string;
  /**
   * 硬件序列号。与 {@link powerbankNo}（业务编号）不是一回事 ——
   * 退换货、保修、跟厂商对故障都只认 sn；只有业务号时要先回查一次映射。
   */
  sn: string | null;
  /**
   * 供应商编码（→ `Vendor.vendorCode`）。**本平台是混合硬件接入**，
   * 同一批故障集中在某个厂商上是第一个要看的信号，而按充电宝号看不出来。
   */
  vendorCode: string | null;
  /** 所在机柜。在库（未入柜）/ 借出 / 丢失的宝为 null（实测后端建档后即为 null）。 */
  cabinetNo: string | null;
  /**
   * 当前所在仓位。借出中（RENTED）为 null —— 它不在任何柜子里。
   * 没有它时，工单只能说「去 CAB1000 找这块充电宝」，找哪一仓靠人逐个看。
   */
  slotIndex: number | null;
  battery: number; // 0..100
  status: PowerbankStatus;
  health: PowerbankHealth;
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
/**
 * 调拨单状态。
 *
 * **此前是内联在 interface 里的联合**（`status: "DRAFT" | …`）——
 * `StatusVocabularyAcrossEndsTest` 的解析器只认具名 `export type`，
 * 于是整个调拨域的词表两端从未被比对过。那条卡口的类注释把这种情况列为已知边界，
 * 并写明「碰到了就把它提成具名类型，顺手也让它进入覆盖」——这就是那一下。
 * 与后端 `InvTransferStatus` 枚举同名同值，改一边另一边会红。
 */
export type InvTransferStatus = "DRAFT" | "IN_TRANSIT" | "DONE";

/** 调拨单上的动作。见 {@link TRANSFER_TRANSITIONS}。 */
export type TransferAction = "ship" | "receive";

/**
 * 调拨单状态机（SSOT）：页面可选项与 mock 校验共用同一份，
 * 与工单 `WO_TRANSITIONS`、提现 `WITHDRAW_TRANSITIONS` 同一套写法。
 * 对齐后端 `InvTransferStateMachine`（SHIP / RECEIVE 两条边，DONE 是终态）。
 *
 * 「已收货的单能不能退回在途」的答案是**不能**：要退货应开一张反向调拨单，
 * 留两条痕，而不是把一条痕改回去。
 *
 * ⚠️ `receive` 后端还有一道 `requireAllChecked` —— 明细必须全部核对过。
 * **那是前置条件，不是状态机的边**，所以不在本表里：塞进来会让
 * 「这一步不让走」和「你还没核对完」变成同一句话，而用户需要知道是哪一种。
 */
export const TRANSFER_TRANSITIONS: Record<TransferAction,
  { from: readonly InvTransferStatus[]; to: InvTransferStatus; label: string }> = {
  ship: { from: ["DRAFT"], to: "IN_TRANSIT", label: "发出" },
  receive: { from: ["IN_TRANSIT"], to: "DONE", label: "确认收货" },
};

export const canTransferAction = (status: InvTransferStatus, action: TransferAction) =>
  TRANSFER_TRANSITIONS[action].from.includes(status);

/**
 * 当前状态下，状态下拉该给哪几个选项 = **保持当前** + 合法的下一步。
 *
 * 此前这个下拉是写死的三选（草稿/在途/已完成），于是一张 DRAFT 的单也能选「已完成」
 * —— 点下去后端按非法迁移拒。界面给得出的选项，后端就该收得下。
 */
export const nextTransferStatuses = (from: InvTransferStatus): InvTransferStatus[] => [
  from,
  ...(Object.keys(TRANSFER_TRANSITIONS) as TransferAction[])
    .filter((a) => canTransferAction(from, a))
    .map((a) => TRANSFER_TRANSITIONS[a].to),
];

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
  status: InvTransferStatus;
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

/** 与后端 `OtaRolloutStatus` 枚举同名同值。**具名不是风格** —— 两端同名词表比对只认
 *  具名 `export type`，内联在 interface 里的联合它一个都发现不了。 */
export type OtaRolloutStatus = "PENDING" | "RUNNING" | "DONE" | "ROLLBACK";
export interface OtaRollout {
  rolloutNo: string;
  /**
   * 投放的是版本库里的**哪一条**（→ `OtaRelease.releaseNo`，表上 NOT NULL）。
   *
   * {@link fwVersion} 只是展示用的版本号文本 —— 同一个版本号在不同厂商下
   * 可能是两个包，靠它无法回答「到底投的哪个包、校验和是多少、是否强制升级」。
   * 版本库（OtaRelease）后来补上了，而**投放指向版本库的这根线一直没接**。
   */
  releaseNo: string;
  fwVersion: string;
  vendorCode: string;
  /**
   * 投放范围。与 {@link strategy} 是**两个维度**：strategy 说「怎么发」
   * （灰度/全量），scope 说「发给谁」（单台 / 整站 / 全部）。
   * 只有 strategy 时，一条灰度投放看不出它究竟影响了多少设备。
   */
  /**
   * **LOCATION 是点位不是站点**。后端 `dev_ota_rollout.scope` 的词表是
   * DEVICE/LOCATION/ALL，实体注释写明「LOCATION 时 targetRef 为 locationNo」。
   * 这里此前写的是 SITE，而本仓 SITE(站点) 与 LOCATION(点位) 是两级不同的东西 ——
   * 按站点投放会把**站点号**塞进后端当作点位号解析的字段，投放目标对不上，
   * 而这件事不报错：任务照常建出来，只是发给了错的一批（或一台都没有）。
   */
  scope: "DEVICE" | "LOCATION" | "ALL";
  /** scope 的目标：DEVICE→柜机号、LOCATION→点位号；ALL 为 null。 */
  targetRef: string | null;
  strategy: "GRAY" | "FULL";
  progress: number; // 0..100
  status: OtaRolloutStatus;
  createdAt: string;
}

/**
 * 固件版本库（`dev_ota_release`）：投放引用的「货架」。
 * 原先前端只有投放（OtaRollout）没有版本库，于是「投的是哪个包、校验和是多少、是否强制升级」
 * 全都无处可看——投放页填的固件版本号只是一个自由文本。
 */
/**
 * 固件发布的生命周期。**五档，与后端 `OtaReleaseStatus` 枚举、
 * `dev_ota_release.status` 的 DDL 词表逐个对过**。
 *
 * 此前这里是内联的四值联合，少了 ARCHIVED —— 已归档的发布在运营端是未知值：
 * 徽标映射不上、按它筛一条都查不到。具名是为了进两端同名词表比对
 * （那个卡口只认具名 `export type`）。
 */
export type OtaReleaseStatus = "DRAFT" | "PUBLISHED" | "PAUSED" | "COMPLETED" | "ARCHIVED";
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
  status: OtaReleaseStatus;
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
/**
 * DeviceCodeType
 *
 * <p>抽成**具名** `export type` 而不是内联在 interface 里：跨端词表卡口
 * `StatusVocabularyAcrossEndsTest` 是「两端同名即比对」——
 * 内联的联合类型它**配不上对，一个字都比不了**。后端同名枚举 `DeviceCodeType` 取值一致。
 */
export type DeviceCodeType = "QR" | "SN";

/**
 * CodeBatchStatus
 *
 * <p>抽成**具名** `export type` 而不是内联在 interface 里：跨端词表卡口
 * `StatusVocabularyAcrossEndsTest` 是「两端同名即比对」——
 * 内联的联合类型它**配不上对，一个字都比不了**。后端同名枚举 `CodeBatchStatus` 取值一致。
 */
export type CodeBatchStatus = "PENDING" | "PARTIAL" | "BOUND" | "VOID";

export interface DeviceCodeBatch {
  batchNo: string;
  vendorCode: string;
  codeType: DeviceCodeType; // 二维码 / 出厂序列号
  rangeStart: string;
  rangeEnd: string;
  total: number;
  bound: number; // 已绑定数（列表显示 已绑定/总数 + 进度条）
  producedAt: string;
  status: CodeBatchStatus;
}

// ─────────────────────────────────────────────────────────────
// 设备运维：上线门禁 · 试借还 · 保护动作（`/api/ops/devices/**`）
// ─────────────────────────────────────────────────────────────

/**
 * 保护动作（SSOT，与后端 `ProtectionAction` 同名同值）。
 *
 * <p>四档按**影响面**从大到小：停租整柜 → 禁用仓位 → 锁定仓位 → 降额。
 * 它们不是互斥的状态，而是**可叠加的约束**：同一台设备可能同时挂着
 * 信号触发的停租和人工挂的仓位禁用，所以下面用「引用计数」的方式解除
 * （见 {@link Protection.holderType}）。
 */
export type ProtectionAction = "STOP_RENT" | "SLOT_DISABLE" | "SLOT_LOCK" | "DERATE";

/**
 * 保护的持有方（`dev_protection.holder_type`）。
 *
 * <p>**这是保护能不能被解除的依据**：信号（SIGNAL）与告警（ALARM）挂的保护
 * 由系统在条件恢复时自己撤，人工（MANUAL）挂的只能人工撤。
 * 不分持有方的话，运维手一抖把告警挂的停租解了，设备会在故障未恢复时重新接客。
 */
export type ProtectionHolderType = "SIGNAL" | "ALARM" | "MANUAL";

/**
 * 试借还状态（SSOT，与后端 `TrialRentStatus` 同名同值）。
 *
 * <p>上线门禁里最硬的一关：**新装的柜子必须真借出一个宝、再真还回去**，
 * 才算证明了弹仓与回收都通。只查配置不试一次的话，
 * 第一个真实用户就是试验品，而那时现场已经没人了。
 */
export type TrialRentStatus = "EJECTING" | "WAIT_RETURN" | "PASSED" | "FAILED" | "EXPIRED";

/** 一次试借还（后端 `TrialRent`）。 */
export interface TrialRent {
  trialNo: string;
  cabinetNo: string;
  slotIndex: number | null;
  powerbankNo: string | null;
  status: TrialRentStatus;
  ejectedAt: string | null;
  returnedAt: string | null;
  failReason: string | null;
  operator: string | null;
  createdAt: string;
}

/** 一条保护（后端 `Protection`）。 */
export interface Protection {
  protectionNo: string;
  cabinetNo: string;
  /** 空 = 整柜级；有值 = 只作用于该仓位。 */
  slotIndex: number | null;
  action: ProtectionAction;
  holderType: ProtectionHolderType;
  /** 持有方的业务号：信号码 / 告警号 / 操作人。解除时要核对它。 */
  holderRef: string | null;
  reason: string | null;
  active: boolean;
  createdAt: string;
  releasedAt: string | null;
  releaseReason: string | null;
}

/** 挂保护的入参。 */
export interface ProtectionReq {
  action: ProtectionAction;
  slotIndex?: number | null;
  reason: string;
}

/** 设备信号码字典（后端 `SignalCode`）—— 设备错误码降级后的「信号」。 */
export interface SignalCode {
  code: string;
  name: string;
  nameEn: string | null;
  /** 分类：通信 / 电源 / 仓位 … */
  category: string | null;
  /** 作用范围：整柜还是仓位。 */
  scope: string | null;
  /**
   * 命中后的止损动作。**比 {@link ProtectionAction} 宽**（实测 `/api/ops/device-signals`）：
   * 「只记录不保护」写的是字面量 `NONE` 而不是 null；仓位卡宝是 `AUTO_EJECT_RETRY`（自动重弹，
   * 不落保护表）。两者都不是保护，页面按「不挂保护」展示。
   */
  protectiveAction: ProtectionAction | "NONE" | "AUTO_EJECT_RETRY" | null;
  /** 哪个信号码能清除它（成对出现的信号，如 离线/恢复）。 */
  clearsCode: string | null;
  /** 它喂给哪些业务告警。信号本身**不是**告警——业务告警才是人要看的那层。 */
  feeds: string | null;
}

// ─────────────────────────────────────────────────────────────
// 机柜 / 充电宝状态机 · 入库质检 · 调拨作业 · 资产差异（批次 5b）
// ─────────────────────────────────────────────────────────────

/** 机柜上的人工动作。IN_TRANSIT 的两条边由调拨单的发货 / 签收驱动，不在这里（见 known-missing-ui-transitions）。 */
export type CabinetAction = "goLive" | "markFault" | "repair" | "undeploy" | "retire";

/**
 * 机柜状态机（SSOT）：详情页的动作按钮与 mock 校验共用同一份。
 * 逐边照抄后端 `CabinetStateMachine`（GO_LIVE / MARK_FAULT / REPAIR / UNDEPLOY / RETIRE），
 * `StateMachineEdgeAcrossEndsTest` 两端比对。
 *
 * 在用（DEPLOYED）**不能直接报废** —— 先撤机回库，否则站点上会留下一台账面已报废、现场还在接客的柜子。
 * 「上线」除了这条边还有一道门禁（`goLiveGate`），门禁是前置条件、不是边，所以不在表里。
 */
export const CABINET_TRANSITIONS: Record<CabinetAction,
  { from: readonly CabinetStatus[]; to: CabinetStatus; label: string; event: string }> = {
  goLive: { from: ["IN_STOCK"], to: "DEPLOYED", label: "上线", event: "GO_LIVE" },
  markFault: { from: ["DEPLOYED"], to: "FAULT", label: "标记故障", event: "MARK_FAULT" },
  repair: { from: ["FAULT"], to: "DEPLOYED", label: "修复完成", event: "REPAIR" },
  undeploy: { from: ["DEPLOYED", "FAULT"], to: "IN_STOCK", label: "撤机回库", event: "UNDEPLOY" },
  retire: { from: ["IN_STOCK", "FAULT"], to: "RETIRED", label: "报废", event: "RETIRE" },
};

export const canCabinetAction = (status: CabinetStatus, action: CabinetAction) =>
  CABINET_TRANSITIONS[action].from.includes(status);

/**
 * 充电宝上的人工动作（方案 §6.4）。借出 / 归还 / 买断 / 投放由订单与设备事件推进，不给按钮。
 * `event` 是发给后端 `POST /powerbanks/{no}` 的事件名（`PowerbankCmd.event`），由后端状态机裁决。
 */
export type PowerbankAction = "reportFault" | "repair" | "recover" | "scrap";

/**
 * 逐边照抄后端 `PowerbankStateMachine` 里人工可触发的那几条（REPORT_FAULT / REPAIR / RECOVER / SCRAP）。
 *
 * **刻意没有「标记丢失」**（RENTED → LOST）：后端 V113 把它收成「疑似丢失 → 人工核实」
 * （`/powerbanks/{no}/confirm-lost`，只对系统打了疑似标记的宝开放 —— 没被系统怀疑过的宝要标丢失，
 * 说明判断依据不在系统里）。用通用事件 OVERDUE 在这里再开一个口子，就绕过了那道核实。
 */
export const POWERBANK_TRANSITIONS: Record<PowerbankAction,
  { from: readonly PowerbankStatus[]; to: PowerbankStatus; label: string; event: string }> = {
  reportFault: { from: ["IN_CABINET", "IN_STOCK"], to: "FAULT", label: "标记故障", event: "REPORT_FAULT" },
  repair: { from: ["FAULT"], to: "IN_STOCK", label: "维修回仓", event: "REPAIR" },
  recover: { from: ["LOST"], to: "IN_CABINET", label: "找回", event: "RECOVER" },
  scrap: { from: ["FAULT", "IN_STOCK", "IN_CABINET"], to: "SCRAP", label: "报废", event: "SCRAP" },
};

export const canPowerbankAction = (status: PowerbankStatus, action: PowerbankAction) =>
  POWERBANK_TRANSITIONS[action].from.includes(status);

/**
 * 入库质检状态（与后端 `QcStatus` 同名同值）。
 * **null 不是一个值**：是质检上线之前就入库的存量设备，后端按「放行」处理（门禁显示「存量设备免检」）。
 * PENDING / FAILED 的设备不能发货调拨、不能上线。
 */
export type QcStatus = "PENDING" | "PASSED" | "FAILED";

/** 质检对象类型（`dev_qc_record.item_type`）。 */
export type QcItemType = "CABINET" | "POWERBANK";

/**
 * 入库质检入参（后端 `QcReq`）。机柜看 powerOn / slotsOk，充电宝看 battery / cycles。
 * `result` 空 = 按检查项判定；人可以把「过了」判成不过（须写 note），**不能把「不过」判成过**。
 */
export interface QcReq {
  powerOn?: boolean | null;
  slotsOk?: boolean | null;
  battery?: number | null;
  cycles?: number | null;
  result?: QcStatus | null;
  note?: string | null;
}

/** 一条质检记录（后端 `QcRecord`）。 */
export interface QcRecord {
  qcNo: string;
  itemType: QcItemType;
  itemNo: string;
  powerOn: boolean | null;
  slotsOk: boolean | null;
  battery: number | null;
  cycles: number | null;
  result: QcStatus;
  note: string | null;
  inspectedBy: string;
  inspectedAt: string;
}

/** 调拨两端的类型（与后端 `TransferEndpointType` 同名同值）：决定 fromRef / toRef 指向仓库、站点还是点位。 */
export type TransferEndpointType = "WAREHOUSE" | "SITE" | "LOCATION";

/** 调拨物类型。后端按它决定明细号是机柜号还是充电宝号，且只有机柜会随发货 / 签收改状态。 */
export type TransferItemType = "CABINET" | "POWERBANK";

/**
 * 调拨单写入面（后端 `InvTransferReq`）。**名字字段是 fromName / toName**，不是列表出参里的
 * fromLocation / toLocation —— 此前表单按出参字段名提交，后端静默忽略，名字永远存不进去。
 * 不含经办人：服务端按当前登录人落。状态也不经这里改（R1）—— 发货 / 签收走专门的端点。
 */
export interface InvTransferReq {
  transferNo?: string;
  fromType: TransferEndpointType;
  fromRef: string;
  fromName?: string | null;
  toType: TransferEndpointType;
  toRef: string;
  toName?: string | null;
  itemType: TransferItemType;
  powerbankCount?: number | null;
}

/** 资产差异处理状态（与后端 `AssetDiffStatus` 同名同值）。处理 = 查清去向并写明结论，不改差异本身。 */
export type AssetDiffStatus = "OPEN" | "RESOLVED";

/** 资产差异种类（与后端 `AssetDiffKind` 同名同值）。 */
export type AssetDiffKind = "MISSING" | "EXTRA" | "COUNT_MISMATCH";

/**
 * 一条资产差异（后端 `AssetDiff`）。来源是调拨签收（sourceType=TRANSFER，sourceRef=调拨单号）
 * 或撤机清点（sourceRef=工单号）。**签收不因差异而卡住** —— 差异逐件落这里等人查清去向。
 */
export interface AssetDiff {
  diffNo: string;
  sourceType: string;
  sourceRef: string;
  kind: AssetDiffKind;
  itemType: TransferItemType;
  /** 件号；COUNT_MISMATCH（撤机清点数对不上）没有具体件号。 */
  itemNo: string | null;
  siteNo: string | null;
  cabinetNo: string | null;
  expectedQty: number | null;
  actualQty: number | null;
  status: AssetDiffStatus;
  resolveNote: string | null;
  resolvedBy: string | null;
  resolvedAt: string | null;
  createdAt: string;
}

/**
 * 签收结果（后端 `ReceiveResult`）。单上有而没收到的记 missing，收到而单上没有的记 extra，
 * 两者都已落成资产差异（diffs）—— 页面据此当场告诉人「少了哪几台」，而不是月底盘点才发现。
 */
export interface ReceiveResult {
  transferNo: string;
  status: InvTransferStatus;
  received: number;
  missing: string[];
  extra: string[];
  diffs: AssetDiff[];
}
