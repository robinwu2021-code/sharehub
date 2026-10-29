// 覆盖范围：机柜与仓位、下发指令、充电宝、实时监控、指令记录、调拨、OTA、设备日志、设备编码批次。
import * as dg from "../../mock/db/device-gate";
import * as da from "../../mock/db/device-asset";
import * as dp from "../../mock/db/device-pool";
import * as ik from "../../mock/db/intake";
import * as ia from "../../mock/db/inventory-admin";
import * as mv from "../../mock/db/movement";
import * as st from "../../mock/db/stock";
import * as db from "../../mock/db";
import type { DeviceApi } from "../contracts/device";
import type { PageQ, CabinetQ, DeviceLogQ, ArchiveQ, OtaReleaseQ } from "../query";
import { wait } from "./_wait";
import { currentAuth } from "../../auth";

export const deviceMock: DeviceApi = {
  listCabinets: (q: CabinetQ = {}) => {
    // 出库池字段回填与演示数据（见 device-pool.ensurePoolSeed；后端 V161 对存量设备同样回填）
    dp.ensurePoolSeed();
    return wait(db.paginate(db.cabinets, q.page, q.size, (c) =>
      dp.poolHit(c, q.issuedAgentNo, q.pool, currentAuth()?.username) &&
      db.liveHit(c, q.showArchived) &&
      // 站点号也进搜索：从站点相关页面（坪效/生命周期）拿到 ST3xx 时能直接搜出这个站的机柜
      db.kwHit(q.keyword, c.cabinetNo, c.locationName, c.siteNo) &&
      (!q.onlineStatus || c.onlineStatus === q.onlineStatus) &&
      (!q.status || c.status === q.status)));
  },
  getCabinet: (no) => (dp.ensurePoolSeed(), wait({ cabinet: db.cabinets.find((c) => c.cabinetNo === no)!, slots: db.slotsOf(no) })),
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
  savePowerbank: async (x) => wait(db.savePowerbank(x), 350),
  // async：非法两端 / 非法迁移是同步抛的，不加 async 就不是 rejected promise，全局 MutationCache 接不到
  saveInventoryTransfer: async (x) => wait(db.saveInventoryTransfer(x), 350),
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
  goLiveGate: async (no) => wait(dg.goLiveGate(no)),
  goLive: async (no) => wait(dg.goLive(no), 350),
  markDeviceFault: async (no, reason) => wait(dg.markDeviceFault(no, reason), 350),
  repairDevice: async (no) => wait(dg.repairDevice(no), 350),
  undeployDevice: async (no, reason) => wait(dg.undeployDevice(no, reason), 350),
  retireDevice: async (no, reason) => wait(dg.retireDevice(no, reason), 350),
  listTrialRents: async (no) => wait(dg.listTrialRents(no)),
  startTrialRent: async (no) => wait(dg.startTrialRent(no), 400),
  listProtections: async (no, activeOnly) => wait(dg.listProtections(no, activeOnly)),
  applyProtection: async (no, req) => wait(dg.applyProtection(no, req), 350),
  releaseProtection: async (pno, reason) => wait(dg.releaseProtection(pno, reason), 350),
  listSignalCodes: () => wait(dg.listSignalCodes()),

  // —— 批次 5b：充电宝动作 · 入库质检 · 调拨作业 · 资产差异 ——
  transitPowerbank: async (no, action) => wait(da.transitPowerbank(no, action), 350),
  inspectCabinet: async (no, req) => wait(da.inspectCabinet(no, req), 350),
  inspectPowerbank: async (no, req) => wait(da.inspectPowerbank(no, req), 350),
  confirmPowerbankLost: async (no, note) => wait(da.confirmPowerbankLost(no, note), 300),
  dismissPowerbankLost: async (no, note) => wait(da.dismissPowerbankLost(no, note), 300),
  listQcRecords: async (itemNo) => wait(da.listQcRecords(itemNo)),
  listDeviceMovements: async (itemNo) => wait(mv.listDeviceMovements(itemNo)),
  getInventoryEngine: async () => wait(st.engine()),
  getStockStages: async () => wait(st.stages()),
  getInventoryMigrationPlan: async () => wait(st.migrationPlan()),
  runInventoryMigration: async (limit) => wait(st.runMigration(limit ?? 200), 600),
  getStockPeriodSummary: async (q) => wait(st.periodSummary(q)),
  listStockLedger: async (q = {}) => wait(st.listStockLedger(q)),
  listStockCounts: async (q = {}) => wait(st.listStockCounts(q)),
  getStockCount: async (no) => wait(st.getStockCount(no)),
  openStockCount: async (wh) => wait(st.openStockCount(wh), 350),
  scanStockCount: async (no, nos) => wait(st.scanStockCount(no, nos), 300),
  submitStockCount: async (no) => wait(st.moveStockCount(no, "OPEN", "SUBMITTED"), 300),
  approveStockCount: async (no) => wait(st.moveStockCount(no, "SUBMITTED", "APPROVED"), 300),
  rejectStockCount: async (no, note) => wait(st.rejectStockCount(no, note), 300),
  postStockCount: async (no) => wait(st.moveStockCount(no, "APPROVED", "POSTED"), 300),
  cancelStockCount: async (no) => wait(st.cancelStockCount(no), 300),
  setTransferItems: async (no, itemNos) => wait(da.setTransferItems(no, itemNos), 350),
  shipTransfer: async (no) => wait(da.shipTransfer(no), 400),
  receiveTransfer: async (no, receivedNos, note) => wait(da.receiveTransfer(no, receivedNos, note), 400),
  listAssetDiffs: async (q = {}) => wait(da.listAssetDiffs(q)),
  resolveAssetDiff: async (diffNo, note) => wait(da.resolveAssetDiff(diffNo, note), 350),

  // —— 出库池与绑定：规则（不变式 I1 ~ I5）在 db 层，这里只延迟透传；async 让同步抛错变成 rejected promise ——
  issueDevices: async (x) => wait(dp.issueDevices(x), 400),
  returnPoolDevices: async (x) => wait(dp.returnDevicesToWarehouse(x), 400),
  claimDevices: async (x) => wait(dp.claimDevices(x, currentAuth()?.username ?? ""), 350),
  redirectIssue: async (x) => wait(dp.redirectIssue(x), 400),
  issuedSummary: async (agentNo) => wait(dp.issuedSummary(agentNo)),
  listWarehouses: async () => wait(dp.listWarehouses()),
  createWarehouse: async (x) => wait(ia.createWarehouse(x), 350),
  updateWarehouse: async (no, x) => wait(ia.updateWarehouse(no, x), 350),
  archiveWarehouse: async (no) => wait(ia.archiveWarehouse(no), 350),
  unarchiveWarehouse: async (no) => wait(ia.unarchiveWarehouse(no), 350),
  listSuppliers: async (q) => wait(ia.listSuppliers(q)),
  createSupplier: async (x) => wait(ia.createSupplier(x), 350),
  updateSupplier: async (no, x) => wait(ia.updateSupplier(no, x), 350),
  archiveSupplier: async (no) => wait(ia.archiveSupplier(no), 350),
  unarchiveSupplier: async (no) => wait(ia.unarchiveSupplier(no), 350),
  listIntakes: async (q) => wait(ik.listIntakes(q)),
  getIntake: async (no) => wait(ik.getIntake(no)),
  createIntake: async (x) => wait(ik.createIntake(x), 400),
  inspectIntakeItem: async (no, type, itemNo, req) => wait(ik.inspectIntakeItem(no, type, itemNo, req), 350),
  inboundIntake: async (no, items) => wait(ik.inboundIntake(no, items), 400),
  disposeIntake: async (no, x) => wait(ik.disposeIntake(no, x), 400),
  bindDevice: async (no, x) => wait(dp.bindDevice(no, x), 400),
  bindDevices: async (x) => wait(dp.bindDevices(x), 500),
  unbindDevice: async (no, reason) => wait(dp.unbindDevice(no, reason), 400),
};
