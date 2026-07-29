// 覆盖范围：系统设置 —— 供应商接入、通知模板 / 发送记录 / 通知黑名单、数据字典、
// 区域、系统参数、开放平台应用、市场国家、支付渠道、业务规则、登录设置、
// App 版本、银行字典、常见问题、税率设置。
// 端点前缀：字典/组织类在 /api/platform/**，B2/B3/B5 新增项在 /api/system/**，
// 供应商接入走 /internal/gw/**（沿用现状，未改动）。
import { client } from "../http-client";
import type { SystemApi } from "../contracts/system";
import type { PageQ, NotifyLogQ, NotifyBlacklistQ, AppVersionQ, BankQ, ProblemQ } from "../query";

export const systemHttp: SystemApi = {
  listVendors: () => client.get("/internal/gw/vendors"),
  saveVendor: (v) => client.post(`/internal/gw/vendors/${v.vendorCode}/config`, v),

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

  // 支付渠道
  listPaymentChannels: (q?: PageQ) => client.get("/api/system/payment-channels", q),
  savePaymentChannel: (x) => client.post(x.channelCode ? `/api/system/payment-channels/${x.channelCode}` : "/api/system/payment-channels", x),

  // 系统设置 B2/B3/B5（规格 §9~§16）
  listNotifyLogs: (q?: NotifyLogQ) => client.get("/api/system/notify-logs", q),
  getNotifyLogStats: () => client.get("/api/system/notify-logs/stats"),
  listNotifyBlacklist: (q?: NotifyBlacklistQ) => client.get("/api/system/notify-blacklist", q),
  saveNotifyBlacklist: (x) => client.post(x.blockNo ? `/api/system/notify-blacklist/${x.blockNo}` : "/api/system/notify-blacklist", x),
  releaseNotifyBlacklist: (no) => client.post(`/api/system/notify-blacklist/${no}/release`, {}),
  getBizRules: () => client.get("/api/system/biz-rules"),
  saveBizRules: (x) => client.post("/api/system/biz-rules", x),
  listLoginSettings: (q?: PageQ) => client.get("/api/system/login-settings", q),
  saveLoginSetting: (x) => client.post(x.country ? `/api/system/login-settings/${x.country}` : "/api/system/login-settings", x),
  listAppVersions: (q?: AppVersionQ) => client.get("/api/system/app-versions", q),
  saveAppVersion: (x) => client.post(x.versionId ? `/api/system/app-versions/${x.versionId}` : "/api/system/app-versions", x),
  rollbackAppVersion: (id) => client.post(`/api/system/app-versions/${id}/rollback`, {}),
  listBanks: (q?: BankQ) => client.get("/api/system/banks", q),
  saveBank: (x) => client.post(x.bankCode ? `/api/system/banks/${x.bankCode}` : "/api/system/banks", x),
  listProblems: (q?: ProblemQ) => client.get("/api/system/problems", q),
  saveProblem: (x) => client.post(x.problemNo ? `/api/system/problems/${x.problemNo}` : "/api/system/problems", x),
  listTaxSettings: (q?: PageQ) => client.get("/api/system/tax-settings", q),
  saveTaxSetting: (x) => client.post(x.country ? `/api/system/tax-settings/${x.country}` : "/api/system/tax-settings", x),
};
