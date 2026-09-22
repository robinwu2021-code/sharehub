// 覆盖范围：机柜与仓位、下发指令、充电宝、实时监控、指令记录、调拨、OTA、设备日志、设备编码批次。
import * as db from "../../mock/db";
import type { DeviceApi } from "../contracts/device";
import type { PageQ, CabinetQ, DeviceLogQ, ArchiveQ, OtaReleaseQ } from "../query";
import { wait } from "./_wait";

export const deviceMock: DeviceApi = {
  listCabinets: (q: CabinetQ = {}) =>
    wait(db.paginate(db.cabinets, q.page, q.size, (c) =>
      db.liveHit(c, q.showArchived) &&
      // 站点号也进搜索：从站点相关页面（坪效/生命周期）拿到 ST3xx 时能直接搜出这个站的机柜
      db.kwHit(q.keyword, c.cabinetNo, c.locationName, c.siteNo) &&
      (!q.onlineStatus || c.onlineStatus === q.onlineStatus) &&
      (!q.status || c.status === q.status))),
  getCabinet: (no) => wait({ cabinet: db.cabinets.find((c) => c.cabinetNo === no)!, slots: db.slotsOf(no) }),
  // async：让 CabinetError / CommandError 变成 rejected promise，交给全局 MutationCache 弹错
  saveCabinet: async (x) => wait(db.saveCabinet(x), 350),
  // 下发即留痕：返回的 commandId 就是刚落库那条记录的号，指令记录 tab 刷新后能立刻认回
  sendCommand: async (no, type, params) => wait({ commandId: db.recordCommand(no, type, params).commandId }, 480),

  // 设备扩展
  listPowerbanks: (q: ArchiveQ = {}) => wait(db.listPowerbanks(q)),
  listCabinetMonitor: (q: PageQ = {}) => wait(db.listCabinetMonitor(q)),
  listCommandRecords: (q: PageQ = {}) => wait(db.listCommandRecords(q)),
  listInventoryTransfers: (q: PageQ = {}) => wait(db.listInventoryTransfers(q)),
  listOtaRollouts: (q: PageQ = {}) => wait(db.listOtaRollouts(q)),
  savePowerbank: (x) => wait(db.savePowerbank(x), 350),
  saveInventoryTransfer: (x) => wait(db.saveInventoryTransfer(x), 350),
  saveOtaRollout: (x) => wait(db.saveOtaRollout(x), 350),

  // 固件 OTA 补齐：版本库 + 逐设备任务
  listOtaReleases: (q: OtaReleaseQ = {}) => wait(db.listOtaReleases(q)),
  saveOtaRelease: (x) => wait(db.saveOtaRelease(x), 350),
  listOtaTasks: (no) => wait(db.listOtaTasks(no)),

  // 批次 B4：设备日志 / 设备编码
  listDeviceLogs: (q: DeviceLogQ = {}) => wait(db.listDeviceLogs(q)),
  listDeviceCodeBatches: (q: PageQ = {}) => wait(db.listDeviceCodeBatches(q)),
  saveDeviceCodeBatch: (x) => wait(db.saveDeviceCodeBatch(x), 350),

  // G1 软删除：归档 / 恢复（禁止物理删除）
  archiveCabinet: async (no) => wait(db.archiveCabinet(no), 350),
  unarchiveCabinet: async (no) => wait(db.unarchiveCabinet(no), 350),
  archivePowerbank: async (no) => wait(db.archivePowerbank(no), 350),
  unarchivePowerbank: async (no) => wait(db.unarchivePowerbank(no), 350),

  // G2 导入：整批校验通过后一次性落库
  importCabinets: async (rows) => wait(db.importCabinets(rows), 600),
};
