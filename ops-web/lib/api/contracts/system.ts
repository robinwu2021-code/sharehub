// 覆盖范围：系统设置 —— 供应商接入、通知模板 / 发送记录 / 通知黑名单、数据字典、
// 区域、系统参数、开放平台应用、市场国家、支付渠道、业务规则、登录设置、
// App 版本、银行字典、常见问题、税率设置。
import type {
  PageQ, NotifyLogQ, NotifyBlacklistQ, AppVersionQ, BankQ, ProblemQ,
} from "../query";
import type {
  PageResult, Vendor, NotifyTemplate, DictEntry, Region, SysParam, OpenApiApp,
  MarketCountry, PaymentChannel, NotifyLog, NotifyLogStats, NotifyBlacklist,
  BizRules, LoginSetting, AppVersion, BankEntry, ProblemEntry, TaxSetting,
} from "../../types";

export interface SystemApi {
  // 供应商接入
  listVendors(): Promise<Vendor[]>;
  saveVendor(v: Partial<Vendor> & { vendorCode: string }): Promise<Vendor>;

  // === 系统扩展 tab ===
  listNotifyTemplates(q?: PageQ): Promise<PageResult<NotifyTemplate>>;
  listDictEntries(q?: PageQ): Promise<PageResult<DictEntry>>;
  listRegions(q?: PageQ): Promise<PageResult<Region>>;
  listSysParams(q?: PageQ): Promise<PageResult<SysParam>>;
  listOpenApiApps(q?: PageQ): Promise<PageResult<OpenApiApp>>;
  listMarketCountries(q?: PageQ): Promise<PageResult<MarketCountry>>;
  saveNotifyTemplate(x: Partial<NotifyTemplate> & { templateNo?: string }): Promise<NotifyTemplate>;
  saveDictEntry(x: Partial<DictEntry> & { dictNo?: string }): Promise<DictEntry>;
  saveRegion(x: Partial<Region> & { regionId?: string }): Promise<Region>;
  saveSysParam(x: Partial<SysParam> & { paramKey?: string }): Promise<SysParam>;
  saveOpenApiApp(x: Partial<OpenApiApp> & { appNo?: string }): Promise<OpenApiApp>;
  saveMarketCountry(x: Partial<MarketCountry> & { countryCode?: string }): Promise<MarketCountry>;

  // === 支付渠道（P1，补齐清单 E8）===
  listPaymentChannels(q?: PageQ): Promise<PageResult<PaymentChannel>>;
  savePaymentChannel(x: Partial<PaymentChannel> & { channelCode?: string }): Promise<PaymentChannel>;

  // === 批次 B2/B3/B5：系统设置 8 项（规格 §9~§16）===
  listNotifyLogs(q?: NotifyLogQ): Promise<PageResult<NotifyLog>>;
  /** 发送记录页头统计：今日发送量 / 失败率 / 今日成本（全量口径，非当页）。 */
  getNotifyLogStats(): Promise<NotifyLogStats>;
  listNotifyBlacklist(q?: NotifyBlacklistQ): Promise<PageResult<NotifyBlacklist>>;
  saveNotifyBlacklist(x: Partial<NotifyBlacklist> & { blockNo?: string }): Promise<NotifyBlacklist>;
  /** 解除拉黑：软删除，把 expireAt 置为当下并保留记录（决策 §八-4）。 */
  releaseNotifyBlacklist(blockNo: string): Promise<NotifyBlacklist>;
  getBizRules(): Promise<BizRules>;
  /** 业务规则分区保存：只传要改的分区（提现 / 预约 / 计费默认值各自一个保存按钮）。 */
  saveBizRules(x: Partial<BizRules>): Promise<BizRules>;
  listLoginSettings(q?: PageQ): Promise<PageResult<LoginSetting>>;
  saveLoginSetting(x: Partial<LoginSetting> & { country?: string }): Promise<LoginSetting>;
  listAppVersions(q?: AppVersionQ): Promise<PageResult<AppVersion>>;
  saveAppVersion(x: Partial<AppVersion> & { versionId?: string }): Promise<AppVersion>;
  /** 版本回滚：置 ROLLBACK 且灰度归零，记录保留。 */
  rollbackAppVersion(versionId: string): Promise<AppVersion>;
  listBanks(q?: BankQ): Promise<PageResult<BankEntry>>;
  saveBank(x: Partial<BankEntry> & { bankCode?: string }): Promise<BankEntry>;
  listProblems(q?: ProblemQ): Promise<PageResult<ProblemEntry>>;
  saveProblem(x: Partial<ProblemEntry> & { problemNo?: string }): Promise<ProblemEntry>;
  listTaxSettings(q?: PageQ): Promise<PageResult<TaxSetting>>;
  saveTaxSetting(x: Partial<TaxSetting> & { country?: string }): Promise<TaxSetting>;
}
