// 覆盖范围：系统/平台域（platform、gw）——租户与租户配置、供应商接入、支付渠道、
// 通知模板与发送记录、触达拉黑、业务规则（提现/预约/计费默认）、登录设置、
// 应用版本、数据字典、地区、银行、问题管理（FAQ）、系统参数、税率、市场国家、OpenAPI 应用。

// —— 租户（platform 域）——
export interface Tenant {
  tenantNo: string;
  name: string;
  brandName: string;
  status: "ENABLED" | "SUSPENDED";
  plan: string;
  cabinetCount: number;
  expireAt: string | null;
}

// —— 租户配置（platform 域，后端兼容层，产品不体现）——
export interface TenantConfig {
  tenantNo: string;
  brandName: string;
  paymentProvider: string; // nearpay（委托，ADR-005）
  currency: string;
  freeMinutes: number;
  capTotal: number;
  enabledVendors: string[];
}

// —— 供应商接入（access-gateway / gw 域）——
export type AccessMode = "TCP" | "MQTT" | "HTTP_API";
export interface Vendor {
  vendorCode: string;
  name: string;
  accessMode: AccessMode;
  status: "ENABLED" | "DISABLED";
  apiBase: string | null;
  deviceCount: number;
}

// —— 系统 · 待建功能补全（platform 域）——
export interface NotifyTemplate {
  templateNo: string;
  name: string;
  channel: "SMS" | "EMAIL" | "PUSH" | "WHATSAPP";
  lang: "ar" | "en";
  status: "ENABLED" | "DISABLED";
}
export interface DictEntry {
  dictNo: string;
  group: string;
  code: string;
  label: string;
  sort: number;
  enabled: boolean;
}
export interface Region {
  regionId: string;
  name: string;
  parent: string;
  level: number;
  cityCount: number;
}
export interface SysParam {
  paramKey: string;
  label: string;
  value: string;
  groupName: string;
  updatedAt: string;
}
export interface OpenApiApp {
  appNo: string;
  name: string;
  appKey: string;
  rateLimit: number;
  status: "ACTIVE" | "DISABLED";
  createdAt: string;
}

// 多国家市场管理架构（系统域 · P3）
export interface MarketCountry {
  countryCode: string; // ISO alpha-2，如 AE
  name: string;
  currency: string;
  timezone: string;
  compliance: string; // 合规主体/牌照
  cityCount: number;
  status: "LIVE" | "PILOT" | "PLANNED"; // 已开城/试点/规划
}

// —— 支付渠道（系统域 · P1，对标简电云 E8）——
// 竞品把 Stripe/Paypal/Braintree/Yedpay/ABA/Selcom 七个渠道各占一个菜单；
// 我们合并为一页：渠道列表 + 各自配置抽屉 —— 少菜单噪音即「信息更清晰」。
export interface PaymentChannel {
  channelCode: string; // NEARPAY / STRIPE / PAYPAL ...
  channelName: string;
  mode: "DELEGATED" | "DIRECT"; // 委托（聚合/代收）/ 直连（自有商户号）
  status: "ENABLED" | "DISABLED";
  countries: string; // 适用国家，ISO alpha-2 逗号分隔，如 "AE,SA"
  currencies: string; // 币种，逗号分隔，如 "AED,SAR"
  capabilities: string; // 能力：支付/退款/预授权/分账，逗号分隔
  apiBase: string;
  merchantId: string;
  apiKeyMasked: string; // 密钥仅掩码展示（真实密钥永不落前端，占位 sk_test_****）
  updatedAt: string;
}

// —— §9 发送记录（系统域 · 阶段 2，对标简电云「短信记录」）——
// 我们更清晰：全渠道（短信/邮件/Push/WhatsApp）而非仅短信，且每条带计费——OTP 是真金白银。
export type NotifyLogChannel = "SMS" | "EMAIL" | "PUSH" | "WHATSAPP";
export interface NotifyLog {
  logNo: string;
  channel: NotifyLogChannel;
  templateNo: string; // 关联通知模板（NT1xx）
  target: string; // 目标（手机/邮箱/push token），**已脱敏中间位**，前端不承载完整联系方式
  scene: string; // 场景：OTP / 订单完成 / 告警 …
  sentAt: string;
  status: "SENT" | "FAILED";
  failReason: string | null;
  cost: number; // 单条计费
  currency: string; // 计费币种（AED）
}
/** 发送记录页头统计（今日发送量 / 失败率 / 今日成本）。 */
export interface NotifyLogStats {
  sentToday: number;
  failedToday: number;
  failRate: number; // 0~1
  costToday: number;
  currency: string;
}

// —— §10 触达拉黑（系统域 · 阶段 2，对标简电云「短信拉黑」）——
// 我们更清晰：全渠道拉黑（含 ALL），且原因是枚举而非自由文本，便于统计退订来源。
export type NotifyBlockReason = "USER_OPT_OUT" | "HARD_BOUNCE" | "ABUSE" | "MANUAL";
export interface NotifyBlacklist {
  blockNo: string; // 业务键（target+channel 是自然键，但复合键不便做行键/编辑，故另立单号）
  target: string; // 号码 / 邮箱
  // 规格 §10 写的是 SMS/EMAIL/PUSH/ALL；这里补上 WHATSAPP —— §9 发送记录已是全渠道，
  // 拉黑若少一个渠道就等于该渠道不可拉黑，与"全渠道拉黑"的对标结论矛盾。
  channel: NotifyLogChannel | "ALL";
  reason: NotifyBlockReason;
  blockedAt: string;
  blockedBy: string;
  expireAt: string | null; // 空 = 永久；「解除」即把到期时间置为当下（软删除，保留审计痕迹）
}

// —— §11 业务规则（系统域 · 阶段 2）——
// 竞品拆「提现设置 / 预约设置 / 充电设置」三个菜单；我们合并为一页三分区，各自保存。
export interface WithdrawRule {
  minAmount: number; // 最低提现额
  feeRate: number; // 手续费率 0~1
  feeCap: number; // 手续费封顶
  settleDays: number; // 结算周期 T+N
  dailyLimit: number; // 单日限额
  needApproval: boolean; // 是否需人工审批
}
export interface ReservationRule {
  maxDurationMin: number; // 预约时长上限（分）
  advanceHours: number; // 提前预约上限（小时）
  holdFeePerMin: number; // 超时未取占位费（元/分）
  maxConcurrent: number; // 单用户同时预约上限
}
export interface BillingDefaultRule {
  freeMinutes: number; // 默认免费时长（分）
  billUnitMinutes: number; // 默认计费单位（分）
  dailyCap: number; // 默认日封顶
  buyoutPrice: number; // 默认买断价
  overdueHours: number; // 超时判定阈值（小时）
}
export interface BizRules {
  withdraw: WithdrawRule;
  reservation: ReservationRule;
  billing: BillingDefaultRule;
  currency: string;
  updatedAt: string;
}

// —— §12 登录设置（系统域 · 阶段 2）——
// 竞品「登录设置 / 第三方登录」两个菜单；我们合并且按国家可配（MENA 多国监管差异大）。
export interface LoginSetting {
  country: string; // ISO alpha-2；`*` = 默认档（置顶）
  countryName: string;
  otpEnabled: boolean;
  passwordEnabled: boolean;
  appleEnabled: boolean;
  googleEnabled: boolean;
  otpExpireSec: number;
  otpDailyLimit: number;
  forceRealName: boolean; // 强制实名（沙特等地合规要求）
}

// —— §13 应用版本（系统域 · 阶段 2）——
// 竞品只有单一版本；我们按平台分 + 灰度比例 + 三语更新说明。
export interface AppVersion {
  versionId: string; // `IOS-1.4.2`：versionNo 在不同平台会重复，故以 平台-版本 为业务键
  versionNo: string;
  platform: "IOS" | "ANDROID" | "H5";
  buildNo: number;
  releaseNote: string;
  releaseNoteEn: string;
  releaseNoteAr: string;
  forceUpdate: boolean;
  minSupported: string; // 最低支持版本（强更时必填）
  rolloutPercent: number; // 灰度比例 0~100
  downloadUrl: string;
  status: "DRAFT" | "RELEASED" | "ROLLBACK";
  releasedAt: string | null;
}

// —— §14 银行管理（系统域 · 阶段 2）——
// 竞品只有行名；我们带国家/币种/IBAN 长度——提现收款账户校验直接读这里。
export interface BankEntry {
  bankCode: string;
  bankName: string;
  bankNameEn: string;
  country: string;
  currency: string;
  swiftPrefix: string;
  ibanLength: number; // IBAN 位数（含国家码），用于提现账户格式校验
  status: "ENABLED" | "DISABLED";
}

// —— §15 问题管理（系统域 · 阶段 2，对标简电云 FAQ）——
// 我们更清晰：三语 + 关联建议处置，直接喂 C 端报障下拉与客服快捷答复。
export type ProblemCategory = "RENT" | "RETURN" | "BILLING" | "DEVICE" | "ACCOUNT" | "OTHER";
export type ProblemAction = "SELF_SERVICE" | "TO_WORKORDER" | "TO_REFUND" | "TO_CS";
export interface ProblemEntry {
  problemNo: string;
  category: ProblemCategory;
  title: string;
  titleEn: string;
  titleAr: string;
  answer: string;
  answerEn: string;
  answerAr: string;
  suggestedAction: ProblemAction;
  sortNo: number;
  status: "ENABLED" | "DISABLED";
}

// —— §16 税率与发票（系统域 · 阶段 3）——
// 竞品「发票设置」只有税率；我们做国家维度配置，与现有「发票」列表互补。
export interface TaxSetting {
  country: string; // ISO alpha-2（业务键）
  countryName: string;
  taxName: string; // VAT / ZATCA VAT …
  ratePercent: number;
  trn: string; // 税号（UAE TRN 15 位）
  invoiceTitle: string; // 默认开票抬头
  includedInPrice: boolean; // 价内税 / 价外税（影响 C 端计费展示）
  effectiveFrom: string;
}
