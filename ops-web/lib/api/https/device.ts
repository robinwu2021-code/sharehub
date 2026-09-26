// 覆盖范围：机柜与仓位、下发指令、充电宝、实时监控、指令记录、调拨、OTA、设备日志、设备编码批次。
// 端点前缀：/api/ops/**
import { client } from "../http-client";
import type { DeviceApi } from "../contracts/device";
import type { PageQ, CabinetQ, DeviceLogQ, ArchiveQ, OtaReleaseQ } from "../query";
import type { Checklist } from "../../types";
import { POWERBANK_TRANSITIONS } from "../../types";

/**
 * 门禁清单 `fixHref` 的兜底改写。
 *
 * <p>**2026-09-26 起后端已按运营端路由出链接**（`FixHrefRoutesTest` 逐条钉住页面与页签），
 * 所以正常情况下这里什么都不做 —— 已是运营端路由的原样返回。
 *
 * <p>留着它是因为：后端一旦又写回路径式（`/devices/CAB1000?tab=qc`），界面上的表现是
 * 「去处理」404，而那是点下去才知道的。这层把已知的旧形状继续翻译过来，
 * 让那种回退**不至于直接砸到用户脸上** —— 但它不再是真源，真源在后端。
 */
export function toOpsHref(href: string | null): string | null {
  if (!href) return href;
  const [path, query = ""] = href.split("?");
  const qs = new URLSearchParams(query);
  const dev = path.match(/^\/devices\/([^/]+)$/);
  if (dev && dev[1] !== "detail") {
    const out = new URLSearchParams({ no: decodeURIComponent(dev[1]) });
    // `?edit=1`（去改点位）落到概览页签：点位编辑在台账的编辑抽屉里，详情页概览给了入口
    const tab = qs.get("tab") ?? (qs.get("edit") ? "overview" : null);
    if (tab) out.set("tab", tab);
    return `/devices/detail?${out.toString()}`;
  }
  const site = path.match(/^\/sites\/([^/]+)$/);
  if (site) {
    const out = new URLSearchParams({ no: decodeURIComponent(site[1]) });
    if (qs.get("tab")) out.set("tab", qs.get("tab")!);
    return `/operation/sites?${out.toString()}`;
  }
  if (path === "/sites") return "/operation/sites";
  if (path === "/work-orders" && !qs.get("view")) {
    qs.set("view", "list");
    return `/work-orders?${qs.toString()}`;
  }
  return href;
}

const withOpsHrefs = (c: Checklist): Checklist => ({
  ...c, items: c.items.map((i) => ({ ...i, fixHref: toOpsHref(i.fixHref) })),
});

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
  // 单号只认路径（写入面里没有 transferNo），所以从 body 里摘掉
  saveInventoryTransfer: ({ transferNo, ...body }) =>
    client.post(transferNo ? `/api/ops/inventory-transfers/${transferNo}` : "/api/ops/inventory-transfers", body),
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
  goLiveGate: (no) => client.get<Checklist>(`/api/ops/devices/${no}/go-live-gate`).then(withOpsHrefs),
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

  // —— 批次 5b：充电宝动作 · 入库质检 · 调拨作业 · 资产差异 ——
  // 充电宝动作复用更新端点，只发事件：后端 PowerbankServiceImpl「event 优先」交状态机裁决
  transitPowerbank: (no, action) =>
    client.post(`/api/ops/powerbanks/${no}`, { event: POWERBANK_TRANSITIONS[action].event }),
  inspectCabinet: (no, req) => client.post(`/api/ops/devices/${no}/qc`, req),
  inspectPowerbank: (no, req) => client.post(`/api/ops/powerbanks/${no}/qc`, req),
  confirmPowerbankLost: (no, note) => client.post(`/api/ops/powerbanks/${no}/confirm-lost`, { note }),
  dismissPowerbankLost: (no, note) => client.post(`/api/ops/powerbanks/${no}/dismiss-lost`, { note }),
  listQcRecords: (itemNo) => client.get("/api/ops/qc-records", { itemNo }),
  setTransferItems: (no, itemNos) => client.post(`/api/ops/inventory-transfers/${no}/items`, { itemNos }),
  shipTransfer: (no) => client.post(`/api/ops/inventory-transfers/${no}/ship`, {}),
  receiveTransfer: (no, receivedNos, note) => client.post(`/api/ops/inventory-transfers/${no}/receive`, { receivedNos, note }),
  listAssetDiffs: (q) => client.get("/api/ops/asset-diffs", q),
  resolveAssetDiff: (diffNo, note) => client.post(`/api/ops/asset-diffs/${diffNo}/resolve`, { note }),
};
