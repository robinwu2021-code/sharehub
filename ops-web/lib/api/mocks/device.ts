// 覆盖范围：机柜与仓位、下发指令、充电宝、实时监控、指令记录、调拨、OTA、设备日志、设备编码批次。
import * as db from "../../mock/db";
import type { DeviceApi } from "../contracts/device";
import type { PageQ, CabinetQ, DeviceLogQ, ArchiveQ } from "../query";
import { wait } from "./_wait";

export const deviceMock: DeviceApi = {
  listCabinets: (q: CabinetQ = {}) =>
    wait(db.paginate(db.cabinets, q.page, q.size, (c) =>
      db.liveHit(c, q.showArchived) &&
      db.kwHit(q.keyword, c.cabinetNo, c.locationName) &&
      (!q.onlineStatus || c.onlineStatus === q.onlineStatus) &&
      (!q.status || c.status === q.status))),
  getCabinet: (no) => wait({ cabinet: db.cabinets.find((c) => c.cabinetNo === no)!, slots: db.slotsOf(no) }),
  sendCommand: (_no, _type) => wait({ commandId: `CMD${Math.floor(performance.now() * 1000)}` }, 480),

  // 设备扩展
  listPowerbanks: (q: ArchiveQ = {}) => wait(db.listPowerbanks(q)),
  listCabinetMonitor: (q: PageQ = {}) => wait(db.listCabinetMonitor(q)),
  listCommandRecords: (q: PageQ = {}) => wait(db.listCommandRecords(q)),
  listInventoryTransfers: (q: PageQ = {}) => wait(db.listInventoryTransfers(q)),
  listOtaRollouts: (q: PageQ = {}) => wait(db.listOtaRollouts(q)),
  savePowerbank: (x) => wait(db.savePowerbank(x), 350),
  saveInventoryTransfer: (x) => wait(db.saveInventoryTransfer(x), 350),
  saveOtaRollout: (x) => wait(db.saveOtaRollout(x), 350),

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
