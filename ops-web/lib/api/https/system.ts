// 覆盖范围：系统设置 —— 供应商接入、通知模板 / 发送记录 / 通知黑名单、数据字典、
// 区域、系统参数、开放平台应用、市场国家、支付渠道、业务规则、登录设置、
// App 版本、银行字典、常见问题、税率设置。
// 端点前缀：字典/组织类在 /api/platform/**，B2/B3/B5 新增项在 /api/platform/**，
// 供应商接入走 /internal/gw/**（沿用现状，未改动）。
import { client } from "../http-client";
import type { SystemApi } from "../contracts/system";
import type { PageQ, NotifyLogQ, NotifyBlacklistQ, AppVersionQ, BankQ, ProblemQ } from "../query";

export const systemHttp: SystemApi = {
  listVendors: () => client.get("/internal/gw/vendors"),
  saveVendor: (v) => client.post(`/internal/gw/vendors/${v.vendorCode}/config`, v),
  // ⚠️ 后端缺口：VendorController 目前只有 GET / 与 POST /{code}/config，没有 /test。
  testVendorConnectivity: (code) => client.post(`/internal/gw/vendors/${code}/test`, {}),

  // 系统扩展
  listNotifyTemplates: (q?: PageQ) => client.get("/api/platform/notify-templates", q),
  listDictEntries: (q?: PageQ) => client.get("/api/platform/dict-entries", q),
  listRegions: (q?: PageQ) => client.get("/api/platform/regions", q),
  listSysParams: (q?: PageQ) => client.get("/api/platform/sys-params", q),
  listOpenApiApps: (q?: PageQ) => client.get("/api/platform/openapi-apps", q),
  listMarketCountries: (q?: PageQ) => client.get("/api/platform/markets", q),
  saveNotifyTemplate: (x) => client.post(x.templateNo ? `/api/platform/notify-templates/${x.templateNo}` : "/api/platform/notify-templates", x),
  saveDictEntry: (x) => client.post(x.dictNo ? `/api/platform/dict-entries/${x.dictNo}` : "/api/platform/dict-entries", x),
  saveRegion: (x) => client.post(x.regionId ? `/api/platform/regions/${x.regionId}` : "/api/platform/regions", x),
  saveSysParam: (x) => client.post(x.paramKey ? `/api/platform/sys-params/${x.paramKey}` : "/api/platform/sys-params", x),
  saveOpenApiApp: (x) => client.post(x.appNo ? `/api/platform/openapi-apps/${x.appNo}` : "/api/platform/openapi-apps", x),
  saveMarketCountry: (x) => client.post(x.countryCode ? `/api/platform/markets/${x.countryCode}` : "/api/platform/markets", x),

  // S6/S7。以下四个端点后端均未实现，接后端前需先补 controller：
  // ⚠️ 后端缺口：SysConfigController 只有 GET/POST /api/platform/regions，没有 /regions/tree。
  listRegionTree: () => client.get("/api/platform/regions/tree"),
  // ⚠️ 后端缺口：NotifyController 有 POST /internal/platform/notify/send（内网真发），
  // 但没有面向运营端的模板预览 / 试发端点。
  previewNotifyTemplate: (no, vars) => client.post(`/api/platform/notify-templates/${no}/preview`, { vars: vars ?? {} }),
  testSendNotifyTemplate: (no, x) => client.post(`/api/platform/notify-templates/${no}/test-send`, x),
  // ⚠️ 后端缺口：SysSettingController 无 /reset-secret；且后端实体目前也没有 app_secret 字段。
  resetOpenApiAppSecret: (no) => client.post(`/api/platform/openapi-apps/${no}/reset-secret`, {}),

  // 支付渠道
  listPaymentChannels: (q?: PageQ) => client.get("/api/platform/payment-channels", q),
  savePaymentChannel: (x) => client.post(x.channelCode ? `/api/platform/payment-channels/${x.channelCode}` : "/api/platform/payment-channels", x),

  // 系统设置 B2/B3/B5（规格 §9~§16）
  listNotifyLogs: (q?: NotifyLogQ) => client.get("/api/platform/notify-logs", q),
  getNotifyLogStats: () => client.get("/api/platform/notify-logs/stats"),
  // ⚠️ 后端缺口：发送记录目前是 append-only 只读，没有 /resend。
  // 幂等键走 body 而非 Idempotency-Key 头：现有 http-client 不支持自定义头，且键要落库可查。
  resendNotifyLog: (no, x) => client.post(`/api/platform/notify-logs/${no}/resend`, x),
  listNotifyBlacklist: (q?: NotifyBlacklistQ) => client.get("/api/platform/notify-blacklist", q),
  saveNotifyBlacklist: (x) => client.post(x.blockNo ? `/api/platform/notify-blacklist/${x.blockNo}` : "/api/platform/notify-blacklist", x),
  releaseNotifyBlacklist: (no) => client.post(`/api/platform/notify-blacklist/${no}/release`, {}),
  getBizRules: () => client.get("/api/platform/biz-rules"),
  saveBizRules: (x) => client.post("/api/platform/biz-rules", x),
  listLoginSettings: (q?: PageQ) => client.get("/api/platform/login-settings", q),
  saveLoginSetting: (x) => client.post(x.country ? `/api/platform/login-settings/${x.country}` : "/api/platform/login-settings", x),
  listAppVersions: (q?: AppVersionQ) => client.get("/api/platform/app-versions", q),
  saveAppVersion: (x) => client.post(x.versionId ? `/api/platform/app-versions/${x.versionId}` : "/api/platform/app-versions", x),
  rollbackAppVersion: (id) => client.post(`/api/platform/app-versions/${id}/rollback`, {}),
  listBrands: (q) => client.get("/api/platform/brands", q),
  saveBrand: (x) => client.post(x.brandNo ? `/api/platform/brands/${x.brandNo}` : "/api/platform/brands", x),
  archiveBrand: (no) => client.post(`/api/platform/brands/${no}/archive`, {}),
  unarchiveBrand: (no) => client.post(`/api/platform/brands/${no}/unarchive`, {}),

  listBanks: (q?: BankQ) => client.get("/api/platform/banks", q),
  saveBank: (x) => client.post(x.bankCode ? `/api/platform/banks/${x.bankCode}` : "/api/platform/banks", x),
  listProblems: (q?: ProblemQ) => client.get("/api/platform/problems", q),
  saveProblem: (x) => client.post(x.problemNo ? `/api/platform/problems/${x.problemNo}` : "/api/platform/problems", x),
  listTaxSettings: (q?: PageQ) => client.get("/api/platform/tax-settings", q),
  saveTaxSetting: (x) => client.post(x.country ? `/api/platform/tax-settings/${x.country}` : "/api/platform/tax-settings", x),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  archiveBank: (code) => client.post(`/api/platform/banks/${code}/archive`, {}),
  unarchiveBank: (code) => client.post(`/api/platform/banks/${code}/unarchive`, {}),
  archiveProblem: (no) => client.post(`/api/platform/problems/${no}/archive`, {}),
  unarchiveProblem: (no) => client.post(`/api/platform/problems/${no}/unarchive`, {}),
};
