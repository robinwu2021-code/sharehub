// 覆盖范围：系统设置 —— 供应商接入、通知模板 / 发送记录 / 通知黑名单、数据字典、
// 区域、系统参数、开放平台应用、市场国家、支付渠道、业务规则、登录设置、
// App 版本、银行字典、常见问题、税率设置。
import * as db from "../../mock/db";
import type { SystemApi } from "../contracts/system";
import type { PageQ, NotifyLogQ, NotifyBlacklistQ, AppVersionQ, BankQ, ProblemQ } from "../query";
import type { Vendor } from "../../types";
import { wait } from "./_wait";

export const systemMock: SystemApi = {
  listVendors: () => wait(db.vendors),
  saveVendor: (v: Partial<Vendor> & { vendorCode: string }) => {
    const idx = db.vendors.findIndex((x) => x.vendorCode === v.vendorCode);
    const merged = { ...(db.vendors[idx] ?? { name: v.vendorCode, accessMode: "HTTP_API", status: "ENABLED", apiBase: null, deviceCount: 0 }), ...v } as Vendor;
    if (idx >= 0) db.vendors[idx] = merged; else db.vendors.push(merged);
    return wait(merged, 350);
  },

  // 系统扩展
  listNotifyTemplates: (q: PageQ = {}) => wait(db.listNotifyTemplates(q)),
  listDictEntries: (q: PageQ = {}) => wait(db.listDictEntries(q)),
  listRegions: (q: PageQ = {}) => wait(db.listRegions(q)),
  listSysParams: (q: PageQ = {}) => wait(db.listSysParams(q)),
  listOpenApiApps: (q: PageQ = {}) => wait(db.listOpenApiApps(q)),
  listMarketCountries: (q: PageQ = {}) => wait(db.listMarketCountries(q)),
  saveNotifyTemplate: (x) => wait(db.saveNotifyTemplate(x), 350),
  saveDictEntry: (x) => wait(db.saveDictEntry(x), 350),
  saveRegion: (x) => wait(db.saveRegion(x), 350),
  saveSysParam: (x) => wait(db.saveSysParam(x), 350),
  saveOpenApiApp: (x) => wait(db.saveOpenApiApp(x), 350),
  saveMarketCountry: (x) => wait(db.saveMarketCountry(x), 350),

  // 支付渠道
  listPaymentChannels: (q: PageQ = {}) => wait(db.listPaymentChannels(q)),
  savePaymentChannel: (x) => wait(db.savePaymentChannel(x), 350),

  // 系统设置 B2/B3/B5（规格 §9~§16）
  listNotifyLogs: (q: NotifyLogQ = {}) => wait(db.listNotifyLogs(q)),
  getNotifyLogStats: () => wait(db.getNotifyLogStats()),
  listNotifyBlacklist: (q: NotifyBlacklistQ = {}) => wait(db.listNotifyBlacklist(q)),
  saveNotifyBlacklist: (x) => wait(db.saveNotifyBlacklist(x), 350),
  releaseNotifyBlacklist: (no) => wait(db.releaseNotifyBlacklist(no), 400),
  getBizRules: () => wait(db.getBizRules()),
  saveBizRules: (x) => wait(db.saveBizRules(x), 350),
  listLoginSettings: (q: PageQ = {}) => wait(db.listLoginSettings(q)),
  saveLoginSetting: (x) => wait(db.saveLoginSetting(x), 350),
  listAppVersions: (q: AppVersionQ = {}) => wait(db.listAppVersions(q)),
  saveAppVersion: (x) => wait(db.saveAppVersion(x), 350),
  rollbackAppVersion: (id) => wait(db.rollbackAppVersion(id), 400),
  listBanks: (q: BankQ = {}) => wait(db.listBanks(q)),
  saveBank: (x) => wait(db.saveBank(x), 350),
  listProblems: (q: ProblemQ = {}) => wait(db.listProblems(q)),
  saveProblem: (x) => wait(db.saveProblem(x), 350),
  listTaxSettings: (q: PageQ = {}) => wait(db.listTaxSettings(q)),
  saveTaxSetting: (x) => wait(db.saveTaxSetting(x), 350),
};
