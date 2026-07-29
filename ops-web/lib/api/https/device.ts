// 覆盖范围：机柜与仓位、下发指令、充电宝、实时监控、指令记录、调拨、OTA、设备日志、设备编码批次。
// 端点前缀：/api/ops/**
import { client } from "../http-client";
import type { DeviceApi } from "../contracts/device";
import type { PageQ, CabinetQ, DeviceLogQ } from "../query";

export const deviceHttp: DeviceApi = {
  listCabinets: (q?: CabinetQ) => client.get("/api/ops/cabinets", q),
  getCabinet: (no) => client.get(`/api/ops/cabinets/${no}`),
  sendCommand: (no, type, params) => client.post(`/api/ops/cabinets/${no}/commands`, { type, params }),

  // 设备扩展
  listPowerbanks: (q?: PageQ) => client.get("/api/ops/powerbanks", q),
  listCabinetMonitor: (q?: PageQ) => client.get("/api/ops/cabinet-monitor", q),
  listCommandRecords: (q?: PageQ) => client.get("/api/ops/command-records", q),
  listInventoryTransfers: (q?: PageQ) => client.get("/api/ops/inventory-transfers", q),
  listOtaRollouts: (q?: PageQ) => client.get("/api/ops/ota-rollouts", q),
  savePowerbank: (x) => client.post(x.powerbankNo ? `/api/ops/powerbanks/${x.powerbankNo}` : "/api/ops/powerbanks", x),
  saveInventoryTransfer: (x) => client.post(x.transferNo ? `/api/ops/inventory-transfers/${x.transferNo}` : "/api/ops/inventory-transfers", x),
  saveOtaRollout: (x) => client.post(x.rolloutNo ? `/api/ops/ota-rollouts/${x.rolloutNo}` : "/api/ops/ota-rollouts", x),

  // 批次 B4：设备日志/编码归 ops 域
  listDeviceLogs: (q?: DeviceLogQ) => client.get("/api/ops/device-logs", q),
  listDeviceCodeBatches: (q?: PageQ) => client.get("/api/ops/device-code-batches", q),
  saveDeviceCodeBatch: (x) => client.post(x.batchNo ? `/api/ops/device-code-batches/${x.batchNo}` : "/api/ops/device-code-batches", x),
};
