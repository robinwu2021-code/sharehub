/**
 * 全渠道通知枚举（台账 T3）。曾有四处各写一套内联枚举
 * （NotifyLogChannel / NotifyTemplate.channel / AlarmNotice.channel / PushMessage.channel），
 * 前三者语义相同故统一到本类型；PushMessage.channel 语义不同（是推送**类型**不是渠道），
 * 已在 marketing.ts 就地注明，未强并。
 */
import type { Archivable } from "./common";

export type NotifyChannel = "SMS" | "EMAIL" | "PUSH" | "WHATSAPP" | "WEBHOOK";

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
  buyoutPrice: number;
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
/**
 * S7 供应商连通性探测结果。
 * 「配置能存」≠「对得上」：接入参数填错要在配置页当场知道，否则等到设备离线告警才发现。
 * 静态阶段是**假探测**（mock 按接入方式/状态推演结论），但结论字段与真探测一致，接后端不改 UI。
 */
export interface VendorProbeResult {
  vendorCode: string;
  ok: boolean;
  endpoint: string; // 实际探测目标：HTTP 型是 apiBase，TCP/MQTT 型是网关侧监听地址
  latencyMs: number;
  checkedAt: string;
  message: string; // 一句话结论，直接给运维看
  detail: string; // 原始信息，排障与找厂商对质用
}

// —— 系统 · 待建功能补全（platform 域）——
/**
 * 模板启用与否。**具名而不是写在 interface 里的内联联合**：
 * 两端同名词表比对（后端 StatusVocabularyAcrossEndsTest）只认具名 `export type`，
 * 内联的联合它一个都发现不了 —— 而这一列正好出过事：
 * 建表默认值给的是 `ACTIVE`，两端都不认识（V65 已改）。
 */
export type NotifyTemplateStatus = "ENABLED" | "DISABLED";
export interface NotifyTemplate {
  templateNo: string;
  name: string;
  channel: "SMS" | "EMAIL" | "PUSH" | "WHATSAPP";
  lang: "ar" | "en";
  status: NotifyTemplateStatus;
  // 下面三个字段后端实体（platform/notify/entity/NotifyTemplate）早就有，前端此前没接——
  // 没有 content 就没法做「预览」，模板页只能改元数据，改不了真正会发出去的文案。
  scene: string; // 场景键：OTP / RENT_OK / RETURN_OK …
  content: string; // 正文，变量写作 {{name}}
  params: string; // 声明的变量名，逗号分隔（预览按此列出待填变量）
}
/** S7 模板预览：变量替换后的成品文案。缺变量要显式列出——带着 {{}} 发出去是事故。 */
export interface NotifyTemplatePreview {
  templateNo: string;
  channel: NotifyTemplate["channel"];
  lang: NotifyTemplate["lang"];
  subject: string; // 邮件标题；非邮件渠道为空串
  rendered: string;
  vars: Record<string, string>; // 本次替换实际用到的取值（未填的用示例值兜底）
  missingVars: string[]; // 模板声明了但既没填也没有示例值的变量
}
/** S7 模板试发：试发也是真发、真计费，故与重发同规格必须带幂等键。 */
export interface NotifyTestSendPayload {
  target: string; // 收件手机/邮箱，落库前脱敏
  vars?: Record<string, string>;
  idempotencyKey: string;
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
  parent: string; // 上级**名称**（列表列直读，历史字段，保留）
  /**
   * 上级 regionId；根节点为 null。树只能按 ID 连边——`parent` 存的是名称，
   * 「Sharjah City」这类名字在多国之间会重复，按名字连边必然连错（拍板 #4 要三级树）。
   */
  parentId: string | null;
  level: number;
  cityCount: number;
}
/** 地区树节点。`level` 必须等于树深度，`parentId` 必须指向存在的节点（mock 与后端同责）。 */
export interface RegionNode extends Region {
  children: RegionNode[];
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
  // AppKey 是公开标识，AppSecret 才是密钥：前端只承载掩码，真实值仅在重置时由后端
  // 经带外渠道交付一次（口径同 PaymentChannel.apiKeyMasked）。
  appSecretMasked: string;
  secretResetAt: string | null; // null = 建档后从未重置
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
/** 发出去了没有。`FAILED` 含「被黑名单拦下」——「没发出去」与「从没尝试过」要分得开。 */
export type NotifyLogStatus = "SENT" | "FAILED";
export interface NotifyLog {
  logNo: string;
  channel: NotifyLogChannel;
  templateNo: string; // 关联通知模板（NT1xx）
  target: string; // 目标（手机/邮箱/push token），**已脱敏中间位**，前端不承载完整联系方式
  scene: string; // 场景：OTP / 订单完成 / 告警 …
  sentAt: string;
  status: NotifyLogStatus;
  failReason: string | null;
  cost: number; // 单条计费
  currency: string; // 计费币种（AED）
  /**
   * 幂等键（拍板 #6）。重发/试发**必须**携带，同键第二次由服务端拒绝——
   * 双击提交或网络重试各落一笔，就是真的多发一条短信、多扣一次钱。历史 seed 为 null。
   */
  idempotencyKey: string | null;
  /** 由哪条记录重发而来；null = 原始发送。重发是**新增一条**，原记录一字不改（审计要看得见发了两次）。 */
  resendOf: string | null;
}
/** 重发入参：只有幂等键——目标/渠道/模板一律沿用原记录，让运营改这些等于换了一次发送。 */
export interface NotifyResendPayload {
  idempotencyKey: string;
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
/**
 * 拉黑是否仍生效。**具名而不是内联联合**：两端同名词表比对
 * （后端 StatusVocabularyAcrossEndsTest）只认具名 `export type`。
 */
export type NotifyBlacklistStatus = "ACTIVE" | "RELEASED";

export interface NotifyBlacklist {
  blockNo: string; // 业务键（target+channel 是自然键，但复合键不便做行键/编辑，故另立单号）
  target: string; // 号码 / 邮箱
  // 规格 §10 写的是 SMS/EMAIL/PUSH/ALL；这里补上 WHATSAPP —— §9 发送记录已是全渠道，
  // 拉黑若少一个渠道就等于该渠道不可拉黑，与"全渠道拉黑"的对标结论矛盾。
  channel: NotifyLogChannel | "ALL";
  reason: NotifyBlockReason;
  blockedAt: string;
  blockedBy: string;
  /**
   * 到期自动失效；空 = 永久。
   *
   * ⚠️ **到期与「被解除」是两条并存的路径，不等价**（后端 NotifyBlacklist 实体注释）。
   * 此处原先写着「『解除』即把到期时间置为当下」—— 那是前端自己的模型，代价有二：
   * 自然到期的条目在界面上被显示成「已解除」（**根本没人解除过它**），
   * 以及合规要问的「何时被谁放开」答不出来。2026-09-24 改读后端的 status/releasedBy。
   */
  expireAt: string | null;
  /**
   * 拉黑是否仍然生效。**判「是否已解除」只看它**，不要再用 expireAt 推 ——
   * `RELEASED` 是有人手工放开，而 expireAt 过去只是到期。
   */
  status: NotifyBlacklistStatus;
  /** 手工解除的时刻与操作人；未解除为 null。合规要能回答「何时被谁放开」。 */
  releasedAt: string | null;
  releasedBy: string | null;
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
  unitMinutes: number; // 默认计费单位（分）
  capDaily: number; // 默认日封顶
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

// —— 品牌（B1，2026-09-23）——
/**
 * 品牌：运营方对 C 端呈现的**经营身份**（名称、Logo、客服、协议）。
 *
 * 两条边界（领域模型 §五）：
 * - **归运营方，代理商不得拥有** —— 代理商用运营方的品牌，C 端无感知，故无 agentNo；
 * - **呈现层不是隔离层** —— 设备与用户账户全平台共享，异地归还跨品牌照常。
 *   做成隔离层等于把 ADR-026 刚砍掉的租户换个名字建回来。
 *
 * 站点侧是 `Site.brandNo` 一列（一站一品牌硬约束），不是多选。
 */
export interface Brand extends Archivable {
  brandNo: string;
  name: string;
  nameEn: string;
  nameAr: string;
  logoUrl: string;
  /** 该品牌的客服电话（C 端「联系客服」用）。 */
  supportPhone: string;
  /** 归属市场；**本期不校验**，待 S2 的区域 → 市场链路。 */
  marketCode?: string | null;
  status: "ENABLED" | "DISABLED";
}

// —— §14 银行管理（系统域 · 阶段 2）——
// 竞品只有行名；我们带国家/币种/IBAN 长度——提现收款账户校验直接读这里。
export interface BankEntry extends Archivable {
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
export interface ProblemEntry extends Archivable {
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
