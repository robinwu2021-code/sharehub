// 覆盖范围：机柜与仓位、下发指令、充电宝、实时监控、指令记录、调拨、OTA、设备日志、设备编码批次。
// 端点前缀：/api/ops/**
import { client } from "../http-client";
import type { DeviceApi } from "../contracts/device";
import type { PageQ, CabinetQ, DeviceLogQ, ArchiveQ, OtaReleaseQ } from "../query";

export const deviceHttp: DeviceApi = {
  listCabinets: (q?: CabinetQ) => client.get("/api/ops/cabinets", q),
  getCabinet: (no) => client.get(`/api/ops/cabinets/${no}`),
  // ⚠️ S8 后端缺口：机柜写端点后端**完全没有**（OpsController 只有 GET /cabinets 与
  // GET /cabinets/{no}；导入端点 /cabinets/import 同样缺）。USE_MOCK=0 时建档/编辑必然 404。
  // 路径按 `x.no ? /coll/{no} : /coll` 的既有约定先占位；后端补齐时注意：`siteNo`/`locationName`
  // 由服务端按 locationNo 反查覆写，不采信请求体（前端也不发这两个字段）。
  saveCabinet: (x) => client.post(x.cabinetNo ? `/api/ops/cabinets/${x.cabinetNo}` : "/api/ops/cabinets", x),
  sendCommand: (no, type, params) => client.post(`/api/ops/cabinets/${no}/commands`, { type, params }),

  // 设备扩展
  listPowerbanks: (q?: ArchiveQ) => client.get("/api/ops/powerbanks", q),
  listCabinetMonitor: (q?: PageQ) => client.get("/api/ops/cabinet-monitor", q),
  listCommandRecords: (q?: PageQ) => client.get("/api/ops/command-records", q),
  listInventoryTransfers: (q?: PageQ) => client.get("/api/ops/inventory-transfers", q),
  getInventoryTransfer: (transferNo) => client.get(`/api/ops/inventory-transfers/${transferNo}`),
  listOtaRollouts: (q?: PageQ) => client.get("/api/ops/ota-rollouts", q),
  savePowerbank: (x) => client.post(x.powerbankNo ? `/api/ops/powerbanks/${x.powerbankNo}` : "/api/ops/powerbanks", x),
  saveInventoryTransfer: (x) => client.post(x.transferNo ? `/api/ops/inventory-transfers/${x.transferNo}` : "/api/ops/inventory-transfers", x),
  saveOtaRollout: (x) => client.post(x.rolloutNo ? `/api/ops/ota-rollouts/${x.rolloutNo}` : "/api/ops/ota-rollouts", x),

  // 固件 OTA 补齐：版本库 + 逐设备任务。版本库**没有** `/{releaseNo}` 更新路由（后端只暴露集合 POST，
  // 由 service 按 body.releaseNo 判 upsert），故这里不套用 `x.no ? /coll/{no} : /coll` 那个约定。
  listOtaReleases: (q?: OtaReleaseQ) => client.get("/api/ops/ota-releases", q),
  saveOtaRelease: (x) => client.post("/api/ops/ota-releases", x),
  listOtaTasks: (no) => client.get(`/api/ops/ota-rollouts/${no}/tasks`),

  // 批次 B4：设备日志/编码归 ops 域
  listDeviceLogs: (q?: DeviceLogQ) => client.get("/api/ops/device-logs", q),
  listDeviceCodeBatches: (q?: PageQ) => client.get("/api/ops/device-code-batches", q),
  saveDeviceCodeBatch: (x) => client.post(x.batchNo ? `/api/ops/device-code-batches/${x.batchNo}` : "/api/ops/device-code-batches", x),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  archiveCabinet: (no) => client.post(`/api/ops/cabinets/${no}/archive`, {}),
  unarchiveCabinet: (no) => client.post(`/api/ops/cabinets/${no}/unarchive`, {}),
  archivePowerbank: (no) => client.post(`/api/ops/powerbanks/${no}/archive`, {}),
  unarchivePowerbank: (no) => client.post(`/api/ops/powerbanks/${no}/unarchive`, {}),

  // G2 导入：服务端整批校验后一次性落库（部分成功不允许）
  importCabinets: (rows) => client.post("/api/ops/cabinets/import", { rows }),

  // —— 设备运维 ——
  goLiveGate: (no) => client.get(`/api/ops/devices/${no}/go-live-gate`),
  goLive: (no) => client.post(`/api/ops/devices/${no}/go-live`, {}),
  markDeviceFault: (no, reason) => client.post(`/api/ops/devices/${no}/mark-fault`, { reason }),
  repairDevice: (no) => client.post(`/api/ops/devices/${no}/repair`, {}),
  undeployDevice: (no, reason) => client.post(`/api/ops/devices/${no}/undeploy`, { reason }),
  retireDevice: (no, reason) => client.post(`/api/ops/devices/${no}/retire`, { reason }),
  listTrialRents: (no) => client.get(`/api/ops/devices/${no}/trial-rents`),
  startTrialRent: (no) => client.post(`/api/ops/devices/${no}/trial-rents`, {}),
  listProtections: (no, activeOnly) => client.get(`/api/ops/devices/${no}/protections`, { activeOnly }),
  applyProtection: (no, req) => client.post(`/api/ops/devices/${no}/protections`, req),
  releaseProtection: (pno, reason) => client.post(`/api/ops/devices/protections/${pno}/release`, { reason }),
  listSignalCodes: () => client.get("/api/ops/device-signals"),
};
