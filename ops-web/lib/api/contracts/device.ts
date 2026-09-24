// 覆盖范围：机柜与仓位、下发指令、充电宝、实时监控、指令记录、调拨、OTA、设备日志、设备编码批次。
import type { PageQ, CabinetQ, DeviceLogQ, ArchiveQ, OtaReleaseQ } from "../query";
import type {
  PageResult, Cabinet, Slot, Powerbank, CabinetMonitor, CommandRecord,
  InventoryTransfer, InventoryTransferDetail, OtaRollout, OtaRelease, OtaTask, DeviceLog, DeviceCodeBatch,
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
  saveInventoryTransfer(x: Partial<InventoryTransfer> & { transferNo?: string }): Promise<InventoryTransfer>;
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
}
