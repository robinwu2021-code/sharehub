// 覆盖范围：机柜与仓位、下发指令、充电宝、实时监控、指令记录、调拨、OTA、设备日志、设备编码批次。
import type { PageQ, CabinetQ, DeviceLogQ, ArchiveQ } from "../query";
import type {
  PageResult, Cabinet, Slot, Powerbank, CabinetMonitor, CommandRecord,
  InventoryTransfer, OtaRollout, DeviceLog, DeviceCodeBatch,
} from "../../types";

export interface DeviceApi {
  listCabinets(q?: CabinetQ): Promise<PageResult<Cabinet>>;
  getCabinet(cabinetNo: string): Promise<{ cabinet: Cabinet; slots: Slot[] }>;
  sendCommand(cabinetNo: string, type: string, params?: Record<string, unknown>): Promise<{ commandId: string }>;

  // === 设备扩展 tab ===
  listPowerbanks(q?: ArchiveQ): Promise<PageResult<Powerbank>>;
  listCabinetMonitor(q?: PageQ): Promise<PageResult<CabinetMonitor>>;
  listCommandRecords(q?: PageQ): Promise<PageResult<CommandRecord>>;
  listInventoryTransfers(q?: PageQ): Promise<PageResult<InventoryTransfer>>;
  listOtaRollouts(q?: PageQ): Promise<PageResult<OtaRollout>>;
  savePowerbank(x: Partial<Powerbank> & { powerbankNo?: string }): Promise<Powerbank>;
  saveInventoryTransfer(x: Partial<InventoryTransfer> & { transferNo?: string }): Promise<InventoryTransfer>;
  saveOtaRollout(x: Partial<OtaRollout> & { rolloutNo?: string }): Promise<OtaRollout>;

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
