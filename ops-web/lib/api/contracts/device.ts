// 覆盖范围：机柜与仓位、下发指令、充电宝、实时监控、指令记录、调拨、OTA、设备日志、设备编码批次。
import type { PageQ, CabinetQ, DeviceLogQ, ArchiveQ, OtaReleaseQ } from "../query";
import type {
  PageResult, Cabinet, Slot, Powerbank, CabinetMonitor, CommandRecord,
  InventoryTransfer, InventoryTransferDetail, OtaRollout, OtaRelease, OtaTask, DeviceLog, DeviceCodeBatch,
  Checklist, TrialRent, Protection, ProtectionReq, SignalCode,
  InvTransferReq, QcReq, QcRecord, ReceiveResult, AssetDiff, AssetDiffStatus, PowerbankAction,
} from "../../types";

export interface DeviceApi {
  listCabinets(q?: CabinetQ): Promise<PageResult<Cabinet>>;
  getCabinet(cabinetNo: string): Promise<{ cabinet: Cabinet; slots: Slot[] }>;
  /**
   * 机柜建档 / 编辑。**入参只含建档字段**：`siteNo`/`locationName` 由 `locationNo` 反查，
   * `agentNo` 只能经代理域划拨，在线态与心跳是设备上报的事实——传了也不采信。
   */
  saveCabinet(x: Partial<Cabinet> & { cabinetNo?: string }): Promise<Cabinet>;
  sendCommand(cabinetNo: string, type: string, params?: Record<string, unknown>): Promise<{ commandId: string }>;

  // === 设备扩展 tab ===
  listPowerbanks(q?: ArchiveQ): Promise<PageResult<Powerbank>>;
  listCabinetMonitor(q?: PageQ): Promise<PageResult<CabinetMonitor>>;
  listCommandRecords(q?: PageQ): Promise<PageResult<CommandRecord>>;
  listInventoryTransfers(q?: PageQ): Promise<PageResult<InventoryTransfer>>;
  /** 调拨单详情：单据 + 明细行。列表那个类型**没有 items**，「具体调了哪几台」只能从这里拿。 */
  getInventoryTransfer(transferNo: string): Promise<InventoryTransferDetail>;
  listOtaRollouts(q?: PageQ): Promise<PageResult<OtaRollout>>;
  savePowerbank(x: Partial<Powerbank> & { powerbankNo?: string }): Promise<Powerbank>;
  /**
   * 建调拨单 / 改草稿单头。入参是后端写入面 `InvTransferReq`（名字字段叫 fromName / toName）。
   * **不改状态**（R1）：发货 / 签收走 {@link shipTransfer} / {@link receiveTransfer}。
   */
  saveInventoryTransfer(x: InvTransferReq): Promise<InventoryTransfer>;
  saveOtaRollout(x: Partial<OtaRollout> & { rolloutNo?: string }): Promise<OtaRollout>;

  // === 固件 OTA 补齐：版本库 + 逐设备任务（后端 DeviceController 早已实现，前端一直没入口）===
  listOtaReleases(q?: OtaReleaseQ): Promise<PageResult<OtaRelease>>;
  /** 建版本。后端只有集合 POST（无 `/{releaseNo}`），故更新也走同一个口、靠 body 里的 releaseNo 判 upsert。 */
  saveOtaRelease(x: Partial<OtaRelease> & { releaseNo?: string }): Promise<OtaRelease>;
  /** 某次投放的逐设备任务。不分页——一次投放的任务量按柜数计，投放详情要的是全量而非翻页。 */
  listOtaTasks(rolloutNo: string): Promise<OtaTask[]>;

  // === 批次 B4：设备日志 / 设备编码（规格 §1 §2）===
  listDeviceLogs(q?: DeviceLogQ): Promise<PageResult<DeviceLog>>;
  listDeviceCodeBatches(q?: PageQ): Promise<PageResult<DeviceCodeBatch>>;
  saveDeviceCodeBatch(x: Partial<DeviceCodeBatch> & { batchNo?: string }): Promise<DeviceCodeBatch>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  archiveCabinet(cabinetNo: string): Promise<Cabinet>;
  unarchiveCabinet(cabinetNo: string): Promise<Cabinet>;
  archivePowerbank(powerbankNo: string): Promise<Powerbank>;
  unarchivePowerbank(powerbankNo: string): Promise<Powerbank>;

  /**
   * G2 导入（全平台唯一的导入口，规格 §10.2）：机柜台账批量落库。
   * **调用方必须先整批校验通过再调**——服务端亦全量校验后再落库，不允许「导一半失败」。
   */
  importCabinets(rows: Partial<Cabinet>[]): Promise<{ imported: number; updated: number }>;

  // ——— 设备运维：上线门禁 · 试借还 · 保护（2026-09-25）———

  /**
   * 上线门禁：这台柜子还差什么才能接客。
   * 与站点开业清单同形（`Checklist`），每条未通过都带 fixHref。
   */
  goLiveGate(deviceNo: string): Promise<Checklist>;
  /** 上线。**门禁不过会被拒** —— 按钮禁用只是提示，真正的闸在服务端。 */
  goLive(deviceNo: string): Promise<Cabinet>;
  /** 标记故障。 */
  markDeviceFault(deviceNo: string, reason?: string): Promise<Cabinet>;
  /** 修复完成，回到可用。 */
  repairDevice(deviceNo: string): Promise<Cabinet>;
  /** 撤机：从站点撤下，回库存。 */
  undeployDevice(deviceNo: string, reason?: string): Promise<Cabinet>;
  /** 报废：**不可逆**。 */
  retireDevice(deviceNo: string, reason?: string): Promise<Cabinet>;

  /** 该柜的试借还记录。 */
  listTrialRents(deviceNo: string): Promise<TrialRent[]>;
  /** 发起一次试借还：真弹一个宝出来，等它还回去。 */
  startTrialRent(deviceNo: string): Promise<TrialRent>;

  /** 该柜当前的保护。`activeOnly=false` 可看历史。 */
  listProtections(deviceNo: string, activeOnly?: boolean): Promise<Protection[]>;
  /** 人工挂一条保护。 */
  applyProtection(deviceNo: string, req: ProtectionReq): Promise<Protection>;
  /**
   * 解除保护。**只能解人工挂的那些** ——
   * 信号/告警挂的由系统在条件恢复时自己撤，手工撤会让设备在故障未恢复时重新接客。
   */
  releaseProtection(protectionNo: string, reason?: string): Promise<Protection>;

  /** 设备信号码字典。信号**不是**告警——业务告警才是人要看的那层。 */
  listSignalCodes(): Promise<SignalCode[]>;

  // ——— 充电宝人工动作 · 入库质检 · 调拨作业 · 资产差异（批次 5b）———

  /**
   * 充电宝人工状态动作（报故障 / 维修回仓 / 标记丢失 / 找回 / 报废）。
   * 发的是**事件**（`PowerbankCmd.event`）而不是目标状态，由后端状态机裁决。
   */
  transitPowerbank(powerbankNo: string, action: PowerbankAction): Promise<Powerbank>;

  /** 机柜入库质检。**只在在库时能做** —— 已布放的设备出问题走故障 / 维修。 */
  inspectCabinet(cabinetNo: string, req: QcReq): Promise<QcRecord>;
  /** 充电宝入库质检（看电量与循环次数，阈值在系统参数里）。 */
  inspectPowerbank(powerbankNo: string, req: QcReq): Promise<QcRecord>;
  /** 某台设备（机柜号或充电宝号）的质检记录，新的在前。 */
  listQcRecords(itemNo: string): Promise<QcRecord[]>;

  /** 草稿期设定调拨明细（整体替换）。每件都要在库且质检已过。 */
  setTransferItems(transferNo: string, itemNos: string[]): Promise<InventoryTransferDetail>;
  /** 发货：DRAFT → IN_TRANSIT；机柜类随之 IN_STOCK → IN_TRANSIT。发货这一刻再核一遍在库与质检。 */
  shipTransfer(transferNo: string): Promise<InventoryTransferDetail>;
  /**
   * 逐件签收：传现场实收的件号。**签收不因差异而卡住** ——
   * 少的记缺件、多的记多件，都落资产差异，返回里当场告诉你是哪几件。
   */
  receiveTransfer(transferNo: string, receivedNos: string[], note?: string): Promise<ReceiveResult>;

  /** 资产差异列表（调拨签收 / 撤机清点产生）。 */
  listAssetDiffs(q?: PageQ & { status?: AssetDiffStatus; sourceType?: string; sourceRef?: string }): Promise<PageResult<AssetDiff>>;
  /** 处理一条差异：写明去向结论。**结论必填** —— 没有结论的「已处理」等于把差异藏起来。 */
  resolveAssetDiff(diffNo: string, note: string): Promise<AssetDiff>;
}
