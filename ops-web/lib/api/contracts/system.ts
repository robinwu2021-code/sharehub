// 覆盖范围：系统设置 —— 供应商接入、通知模板 / 发送记录 / 通知黑名单、数据字典、
// 区域、系统参数、开放平台应用、市场国家、支付渠道、业务规则、登录设置、
// App 版本、银行字典、常见问题、税率设置。
import type {
  PageQ, ArchiveQ, NotifyLogQ, NotifyBlacklistQ, AppVersionQ, BankQ, ProblemQ,
} from "../query";
import type {
  PageResult, Vendor, NotifyTemplate, DictEntry, Region, SysParam, OpenApiApp,
  MarketCountry, PaymentChannel, NotifyLog, NotifyLogStats, NotifyBlacklist,
  BizRules, LoginSetting, AppVersion, Brand, BankEntry, ProblemEntry, TaxSetting,
  VendorProbeResult, RegionNode, NotifyTemplatePreview, NotifyTestSendPayload,
  NotifyResendPayload,
} from "../../types";

export interface SystemApi {
  // 供应商接入
  listVendors(): Promise<Vendor[]>;
  saveVendor(v: Partial<Vendor> & { vendorCode: string }): Promise<Vendor>;
  /** S7 连通性测试：不改任何配置，只回一次探测结论（静态阶段是假探测，字段与真探测一致）。 */
  testVendorConnectivity(vendorCode: string): Promise<VendorProbeResult>;

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

  // === S6/S7 形态与收尾（拍板 #4 树形 / #6 幂等重发）===
  /**
   * S6 地区树：**不分页**。三级区域分页返回时树必然被截断（第 2 页的区拿不到第 1 页的市做父节点），
   * 所以树是独立端点，由服务端保证 parentId 无孤儿、level 等于深度。
   */
  listRegionTree(): Promise<RegionNode[]>;
  /** S7 模板预览：变量替换后的成品文案，不发送、不计费、不留痕。 */
  previewNotifyTemplate(templateNo: string, vars?: Record<string, string>): Promise<NotifyTemplatePreview>;
  /** S7 模板试发：真发一条到指定目标，落一条发送记录（故返回 NotifyLog）；必须带幂等键。 */
  testSendNotifyTemplate(templateNo: string, x: NotifyTestSendPayload): Promise<NotifyLog>;
  /** S7 密钥重置：新 AppSecret 只回掩码，真实值由后端带外交付——前端永不承载真实密钥。 */
  resetOpenApiAppSecret(appNo: string): Promise<OpenApiApp>;

  // === 支付渠道（P1，补齐清单 E8）===
  listPaymentChannels(q?: PageQ): Promise<PageResult<PaymentChannel>>;
  savePaymentChannel(x: Partial<PaymentChannel> & { channelCode?: string }): Promise<PaymentChannel>;

  // === 批次 B2/B3/B5：系统设置 8 项（规格 §9~§16）===
  listNotifyLogs(q?: NotifyLogQ): Promise<PageResult<NotifyLog>>;
  /** 发送记录页头统计：今日发送量 / 失败率 / 今日成本（全量口径，非当页）。 */
  getNotifyLogStats(): Promise<NotifyLogStats>;
  /**
   * 重发（拍板 #6）：**新增**一条发送记录并返回它，原记录不变。
   * `idempotencyKey` 必填且全局唯一，同键第二次必须被拒——否则重复扣费、重复骚扰用户。
   */
  resendNotifyLog(logNo: string, x: NotifyResendPayload): Promise<NotifyLog>;
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
  /** 品牌字典（B1）。站点的「以哪个品牌运营」从这里选。 */
  listBrands(q?: ArchiveQ): Promise<PageResult<Brand>>;
  saveBrand(x: Partial<Brand> & { brandNo?: string }): Promise<Brand>;
  archiveBrand(brandNo: string): Promise<Brand>;
  unarchiveBrand(brandNo: string): Promise<Brand>;

  listBanks(q?: BankQ): Promise<PageResult<BankEntry>>;
  saveBank(x: Partial<BankEntry> & { bankCode?: string }): Promise<BankEntry>;
  listProblems(q?: ProblemQ): Promise<PageResult<ProblemEntry>>;
  saveProblem(x: Partial<ProblemEntry> & { problemNo?: string }): Promise<ProblemEntry>;
  listTaxSettings(q?: PageQ): Promise<PageResult<TaxSetting>>;
  saveTaxSetting(x: Partial<TaxSetting> & { country?: string }): Promise<TaxSetting>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  archiveBank(bankCode: string): Promise<BankEntry>;
  unarchiveBank(bankCode: string): Promise<BankEntry>;
  archiveProblem(problemNo: string): Promise<ProblemEntry>;
  unarchiveProblem(problemNo: string): Promise<ProblemEntry>;
}
