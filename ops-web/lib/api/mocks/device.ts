// 覆盖范围：机柜与仓位、下发指令、充电宝、实时监控、指令记录、调拨、OTA、设备日志、设备编码批次。
import * as dg from "../../mock/db/device-gate";
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
  // async：单号查不到时 notFound 是同步抛的，不加 async 就不是 rejected promise，
  // 抽屉会一直停在 loading 而不是显示「查不到」（同 saveSite 那处的理由）
  getInventoryTransfer: async (transferNo) => wait(db.getInventoryTransfer(transferNo)),
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

  // —— 设备运维：上线门禁 / 试借还 / 保护 ——
  goLiveGate: (no) => wait(dg.goLiveGate(no)),
  goLive: async (no) => wait(dg.goLive(no), 350),
  markDeviceFault: async (no, reason) => wait(dg.markDeviceFault(no, reason), 350),
  repairDevice: async (no) => wait(dg.repairDevice(no), 350),
  undeployDevice: async (no, reason) => wait(dg.undeployDevice(no, reason), 350),
  retireDevice: async (no, reason) => wait(dg.retireDevice(no, reason), 350),
  listTrialRents: (no) => wait(dg.listTrialRents(no)),
  startTrialRent: async (no) => wait(dg.startTrialRent(no), 400),
  listProtections: (no, activeOnly) => wait(dg.listProtections(no, activeOnly)),
  applyProtection: async (no, req) => wait(dg.applyProtection(no, req), 350),
  releaseProtection: async (pno, reason) => wait(dg.releaseProtection(pno, reason), 350),
  listSignalCodes: () => wait(dg.listSignalCodes()),
};
