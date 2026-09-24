// 系统域：通知模板 / 数据字典 / 区域 / 系统参数 / 开放平台应用 / 多国家市场 / 支付渠道，
// 以及系统设置待建 8 项（规格 §9~§16）：发送记录 · 触达拉黑 · 业务规则 · 登录设置 ·
// 应用版本 · 银行管理 · 问题管理 · 税率与发票。
// 口径：MENA 市场（AE/SA/EG…）、币种 AED、引用现有编号（CAB1000+ / ORD5000xx / U30xx / NT1xx）。
// 禁止写入任何真实密钥/真实联系方式：目标一律脱敏，税号/账号用占位。
import type {
  NotifyTemplate, DictEntry, Region, RegionNode, SysParam, OpenApiApp, MarketCountry, PaymentChannel,
  NotifyLog, NotifyLogStats, NotifyBlacklist, BizRules, LoginSetting,
  AppVersion, Brand, BankEntry, ProblemEntry, TaxSetting, PageQuery,
  VendorProbeResult, NotifyTemplatePreview, NotifyTestSendPayload, NotifyResendPayload,
} from "../../types";
import { p, iso } from "./internal";
import { ApiError } from "@/lib/api/error";
import { fail, notFound } from "@/lib/biz-error";
import { validateAppVersion, validateBank } from "../../rules/operation-rules";
import { paginate, kwHit, upsert, nextNo, liveHit, archiveRow, unarchiveRow } from "./helpers";
// 供应商台账住在 device.ts（设备域），连通性探测是系统设置页的动作，故读取而不搬迁。
import { vendors } from "./device";

// —— 通知模板 / 字典 / 区域 / 参数 / 开放平台 ——
// 模板文案与变量：预览要拿真东西替换，所以 content/params 必须成对自洽——
// params 里的每个变量都得在 content 里出现，否则「缺变量」提示会永远报同一个假缺口。
const TEMPLATE_SEEDS = [
  { name: "借出成功通知", scene: "RENT_OK", content: "{{userName}}，您已在 {{siteName}} 借出充电宝 {{powerbankNo}}，前 {{freeMinutes}} 分钟免费。", params: "userName,siteName,powerbankNo,freeMinutes" },
  { name: "归还提醒", scene: "RETURN_REMIND", content: "{{userName}}，充电宝已使用 {{hours}} 小时，当前费用 {{amount}} {{currency}}，请就近归还。", params: "userName,hours,amount,currency" },
  { name: "扣费通知", scene: "CHARGE", content: "订单 {{orderNo}} 已结算，扣款 {{amount}} {{currency}}。", params: "orderNo,amount,currency" },
  { name: "验证码", scene: "OTP", content: "验证码 {{code}}，{{expireMin}} 分钟内有效，请勿转发给任何人。", params: "code,expireMin" },
  { name: "工单派单通知", scene: "WO_DISPATCH", content: "工单 {{woNo}} 已派给您：{{siteName}}，请在 {{deadline}} 前到场。", params: "woNo,siteName,deadline" },
  { name: "提现结果", scene: "WITHDRAW_RESULT", content: "提现 {{amount}} {{currency}} 已{{result}}，预计 {{settleDays}} 个工作日到账。", params: "amount,currency,result,settleDays" },
  // 营销模板故意留一个「声明了却没有示例值」的变量（couponName），让预览的缺变量提示在 mock 下就看得见
  { name: "营销推送", scene: "PROMO", content: "{{title}}：{{body}}（活动券：{{couponName}}）", params: "title,body,couponName" },
];
export const notifyTemplates: NotifyTemplate[] = Array.from({ length: 14 }, (_, i) => {
  const seed = p(TEMPLATE_SEEDS, i);
  return {
    templateNo: `NT${100 + i}`, name: seed.name, scene: seed.scene, content: seed.content, params: seed.params,
    channel: p(["SMS", "EMAIL", "PUSH", "WHATSAPP"] as const, i), lang: i % 2 === 0 ? "ar" : "en",
    status: i % 7 === 0 ? "DISABLED" : "ENABLED",
  };
});
export const dictEntries: DictEntry[] = Array.from({ length: 20 }, (_, i) => ({
  dictNo: `DC${1000 + i}`, group: p(["order_status", "wo_type", "scene_type", "pay_channel"], i),
  code: p(["IN_USE", "FAULT", "MALL", "NEARPAY", "SETTLED", "REFILL"], i),
  label: p(["使用中", "故障", "商场", "NearPay", "已结算", "补货"], i), sort: i + 1, enabled: i % 9 !== 0,
}));
// 三级区域：国家 → 城市/酋长国 → 商圈。`parentId` 与 `level` 必须自洽（level = 深度，
// 且 parentId 必指向已存在的行），否则树上会冒出孤儿父节点——integrity 测试守这条。
export const regions: Region[] = [
  { regionId: "AE", name: "阿联酋", parent: "-", parentId: null, level: 1, cityCount: 7 },
  { regionId: "AE-DU", name: "迪拜", parent: "阿联酋", parentId: "AE", level: 2, cityCount: 1 },
  { regionId: "AE-AZ", name: "阿布扎比", parent: "阿联酋", parentId: "AE", level: 2, cityCount: 1 },
  { regionId: "AE-SH", name: "沙迦", parent: "阿联酋", parentId: "AE", level: 2, cityCount: 1 },
  { regionId: "DU-MAR", name: "Dubai Marina", parent: "迪拜", parentId: "AE-DU", level: 3, cityCount: 0 },
  { regionId: "DU-DEI", name: "Deira", parent: "迪拜", parentId: "AE-DU", level: 3, cityCount: 0 },
  { regionId: "DU-DT", name: "Downtown Dubai", parent: "迪拜", parentId: "AE-DU", level: 3, cityCount: 0 },
  { regionId: "DU-DXB", name: "DXB 机场", parent: "迪拜", parentId: "AE-DU", level: 3, cityCount: 0 },
  { regionId: "AZ-YAS", name: "Yas Island", parent: "阿布扎比", parentId: "AE-AZ", level: 3, cityCount: 0 },
  { regionId: "AZ-COR", name: "Corniche", parent: "阿布扎比", parentId: "AE-AZ", level: 3, cityCount: 0 },
  { regionId: "SH-CIT", name: "Sharjah City", parent: "沙迦", parentId: "AE-SH", level: 3, cityCount: 0 },
  { regionId: "AE-AJ", name: "阿治曼", parent: "阿联酋", parentId: "AE", level: 2, cityCount: 1 },
];
export const sysParams: SysParam[] = [
  { paramKey: "deposit.default", label: "默认押金", value: "50", groupName: "计费", updatedAt: iso(0) },
  { paramKey: "free.minutes", label: "免费时长(分钟)", value: "5", groupName: "计费", updatedAt: iso(86400_000) },
  { paramKey: "cap.daily", label: "每日封顶", value: "30", groupName: "计费", updatedAt: iso(2 * 86400_000) },
  { paramKey: "cap.total", label: "买断价", value: "60", groupName: "计费", updatedAt: iso(3 * 86400_000) },
  { paramKey: "currency", label: "结算币种", value: "AED", groupName: "全局", updatedAt: iso(4 * 86400_000) },
  { paramKey: "pay.provider", label: "支付通道", value: "nearpay", groupName: "支付", updatedAt: iso(5 * 86400_000) },
  { paramKey: "vat.rate", label: "增值税率", value: "0.05", groupName: "财务", updatedAt: iso(6 * 86400_000) },
  { paramKey: "sla.response", label: "默认响应时限(分钟)", value: "30", groupName: "工单", updatedAt: iso(7 * 86400_000) },
  { paramKey: "heartbeat.timeout", label: "心跳超时(秒)", value: "180", groupName: "设备", updatedAt: iso(8 * 86400_000) },
  { paramKey: "ota.strategy", label: "默认升级策略", value: "GRAY", groupName: "设备", updatedAt: iso(9 * 86400_000) },
  { paramKey: "lang.default", label: "默认语言", value: "ar", groupName: "全局", updatedAt: iso(10 * 86400_000) },
  { paramKey: "map.provider", label: "地图服务", value: "google", groupName: "全局", updatedAt: iso(11 * 86400_000) },
  { paramKey: "sms.provider", label: "短信通道", value: "unifonic", groupName: "通知", updatedAt: iso(12 * 86400_000) },
  { paramKey: "whatsapp.enabled", label: "WhatsApp通知", value: "true", groupName: "通知", updatedAt: iso(13 * 86400_000) },
  { paramKey: "credit.min", label: "最低信用分", value: "550", groupName: "风控", updatedAt: iso(14 * 86400_000) },
  { paramKey: "invite.reward", label: "邀请奖励(AED)", value: "5", groupName: "营销", updatedAt: iso(15 * 86400_000) },
];
export const openApiApps: OpenApiApp[] = Array.from({ length: 12 }, (_, i) => ({
  appNo: `APP${300 + i}`, name: p(["Careem 集成", "Noon 广告平台", "Emirates NBD 支付", "第三方BI", "场地方门户", "代理商开放平台"], i),
  appKey: `ak_${String(1000000000 + i * 7654321).slice(0, 10)}`, rateLimit: p([100, 300, 500, 1000], i),
  status: i % 5 === 0 ? "DISABLED" : "ACTIVE", createdAt: iso(i * 172800_000),
  // 掩码占位：mock 里也不写完整密钥形状，避免被当成"真密钥长这样"照抄
  appSecretMasked: "sk_live_****",
  // 只给前两个应用铺"已重置过"的痕迹，其余保持 null，让两种状态在列表里都看得到
  secretResetAt: i < 2 ? iso((i + 3) * 86400_000) : null,
}));

export const marketCountries: MarketCountry[] = [
  { countryCode: "AE", name: "阿联酋", currency: "AED", timezone: "Asia/Dubai", compliance: "Neargo FZ-LLC", cityCount: 5, status: "LIVE" },
  { countryCode: "SA", name: "沙特", currency: "SAR", timezone: "Asia/Riyadh", compliance: "筹备中", cityCount: 2, status: "PILOT" },
  { countryCode: "QA", name: "卡塔尔", currency: "QAR", timezone: "Asia/Qatar", compliance: "规划", cityCount: 0, status: "PLANNED" },
  { countryCode: "KW", name: "科威特", currency: "KWD", timezone: "Asia/Kuwait", compliance: "规划", cityCount: 0, status: "PLANNED" },
  { countryCode: "EG", name: "埃及", currency: "EGP", timezone: "Africa/Cairo", compliance: "规划", cityCount: 0, status: "PLANNED" },
];

export const listNotifyTemplates = (q: PageQuery = {}) => paginate(notifyTemplates, q.page, q.size, (x) => kwHit(q.keyword, x.templateNo, x.name));
export const listDictEntries = (q: PageQuery = {}) => paginate(dictEntries, q.page, q.size, (x) => kwHit(q.keyword, x.dictNo, x.group, x.code, x.label));
export const listRegions = (q: PageQuery = {}) => paginate(regions, q.page, q.size, (x) => kwHit(q.keyword, x.regionId, x.name, x.parent));
export const listSysParams = (q: PageQuery = {}) => paginate(sysParams, q.page, q.size, (x) => kwHit(q.keyword, x.paramKey, x.label, x.groupName));
export const listOpenApiApps = (q: PageQuery = {}) => paginate(openApiApps, q.page, q.size, (x) => kwHit(q.keyword, x.appNo, x.name, x.appKey));
export const listMarketCountries = (q: PageQuery = {}) => paginate(marketCountries, q.page, q.size, (x) => kwHit(q.keyword, x.countryCode, x.name, x.currency));

export const saveNotifyTemplate = (x: Partial<NotifyTemplate>) => upsert(notifyTemplates, x, "templateNo", () => nextNo("NT", notifyTemplates));
export const saveDictEntry = (x: Partial<DictEntry>) => upsert(dictEntries, x, "dictNo", () => nextNo("DC", dictEntries));
export const saveRegion = (x: Partial<Region>) => upsert(regions, x, "regionId", () => nextNo("REG", regions));
export const saveSysParam = (x: Partial<SysParam>) => upsert(sysParams, x, "paramKey", () => nextNo("param.", sysParams));
export const saveOpenApiApp = (x: Partial<OpenApiApp>) => upsert(openApiApps, x, "appNo", () => nextNo("APP", openApiApps));
// 多国家市场：主键是 ISO alpha-2 国家码，由表单必填（不自动生成编号）
export const saveMarketCountry = (x: Partial<MarketCountry>) =>
  upsert(marketCountries, x, "countryCode", () => nextNo("XX", marketCountries, 0));

/**
 * S6 地区树（拍板 #4）：按 `parentId` 连边，不分页——分页会把树截断。
 * `parentId` 指向不存在的行（脏数据）时，该节点冒到顶层而不是被丢掉：
 * 树上少一个区，运营会以为"没建"而重复建档；冒到顶层至少看得见、能修。
 */
export function listRegionTree(): RegionNode[] {
  const nodes = new Map<string, RegionNode>(regions.map((r) => [r.regionId, { ...r, children: [] }]));
  const roots: RegionNode[] = [];
  for (const node of nodes.values()) {
    const parent = node.parentId ? nodes.get(node.parentId) : undefined;
    if (parent) parent.children.push(node); else roots.push(node);
  }
  const sortDeep = (ns: RegionNode[]) => {
    ns.sort((a, b) => a.level - b.level || a.regionId.localeCompare(b.regionId));
    ns.forEach((n) => sortDeep(n.children));
  };
  sortDeep(roots);
  return roots;
}

// ============================================================================
// S7 供应商连通性测试（假探测）
// ----------------------------------------------------------------------------
// 结论是**推演**出来的而不是随机的：同一个供应商每次点都得给同样的答案，
// 否则运维分不清"配置真有问题"还是"探测本身不稳"。
// ============================================================================
export class VendorProbeError extends Error {
  constructor(msg: string) { super(msg); this.name = "VendorProbeError"; }
}
export function testVendorConnectivity(vendorCode: string): VendorProbeResult {
  const v = vendors.find((x) => x.vendorCode === vendorCode);
  if (!v) throw new VendorProbeError(`供应商 ${vendorCode} 不存在`);
  // 延迟由供应商码派生：稳定、可断言，且各家不同（看起来像真的）
  const latencyMs = 40 + ([...vendorCode].reduce((s, c) => s + c.charCodeAt(0), 0) % 260);
  const endpoint = v.accessMode === "HTTP_API"
    ? (v.apiBase ?? "")
    : `gw://${v.accessMode.toLowerCase()}.gateway.internal/${v.vendorCode}`;
  const base = { vendorCode, endpoint, checkedAt: iso(0) };

  if (v.status === "DISABLED") {
    return { ...base, ok: false, latencyMs: 0, message: "供应商已停用，未发起探测", detail: "vendor.status=DISABLED：停用状态下网关不会建立连接，请先启用再测。" };
  }
  if (v.accessMode === "HTTP_API" && !v.apiBase) {
    return { ...base, ok: false, latencyMs: 0, message: "云对接型缺 API 基址，无法探测", detail: "vendor.apiBase 为空：HTTP 云对接必须填基址，否则 driver 无处发起请求。" };
  }
  return {
    ...base, ok: true, latencyMs,
    message: `连通正常 · ${latencyMs}ms`,
    detail: v.accessMode === "HTTP_API"
      ? `GET ${endpoint}/ping → 200 OK（${latencyMs}ms）`
      : `${v.accessMode} 握手成功，已收到心跳帧（${latencyMs}ms）`,
  };
}

// ============================================================================
// 支付渠道（系统域 · P1，对标简电云 E8）
// 一页承载渠道列表 + 配置抽屉：NEARPAY 为当前主通道，其余为未来可插拔占位。
// 注意：mock 不写任何真实密钥，一律占位掩码。
// ============================================================================
export const paymentChannels: PaymentChannel[] = [
  {
    channelCode: "NEARPAY", channelName: "NearPay（聚合收单）", mode: "DELEGATED", status: "ENABLED",
    countries: "AE", currencies: "AED", capabilities: "支付,退款,预授权,分账",
    apiBase: "https://api.nearpay.example", merchantId: "MID-AE-100286",
    apiKeyMasked: "sk_test_****", updatedAt: iso(2 * 86400_000),
  },
  {
    channelCode: "STRIPE", channelName: "Stripe", mode: "DIRECT", status: "DISABLED",
    countries: "AE,SA", currencies: "AED,SAR,USD", capabilities: "支付,退款,预授权",
    apiBase: "https://api.stripe.com", merchantId: "acct_****",
    apiKeyMasked: "sk_test_****", updatedAt: iso(20 * 86400_000),
  },
  {
    channelCode: "PAYPAL", channelName: "PayPal", mode: "DIRECT", status: "DISABLED",
    countries: "AE,EG", currencies: "USD,EUR", capabilities: "支付,退款",
    apiBase: "https://api-m.paypal.com", merchantId: "PP-****",
    apiKeyMasked: "sk_test_****", updatedAt: iso(30 * 86400_000),
  },
  {
    channelCode: "TAP", channelName: "Tap Payments（海湾本地卡）", mode: "DIRECT", status: "DISABLED",
    countries: "AE,SA,KW,BH", currencies: "AED,SAR,KWD,BHD", capabilities: "支付,退款,预授权",
    apiBase: "https://api.tap.company", merchantId: "MID-GCC-****",
    apiKeyMasked: "sk_test_****", updatedAt: iso(35 * 86400_000),
  },
  {
    channelCode: "CHECKOUT", channelName: "Checkout.com", mode: "DIRECT", status: "DISABLED",
    countries: "AE,SA,QA", currencies: "AED,SAR,QAR", capabilities: "支付,退款,预授权,分账",
    apiBase: "https://api.checkout.com", merchantId: "MID-CKO-****",
    apiKeyMasked: "sk_test_****", updatedAt: iso(45 * 86400_000),
  },
  {
    channelCode: "HYPERPAY", channelName: "HyperPay（沙特本地）", mode: "DELEGATED", status: "DISABLED",
    countries: "SA,JO,EG", currencies: "SAR,JOD,EGP", capabilities: "支付,退款",
    apiBase: "https://eu-prod.oppwa.com", merchantId: "MID-SA-****",
    apiKeyMasked: "sk_test_****", updatedAt: iso(50 * 86400_000),
  },
];

export const listPaymentChannels = (q: PageQuery = {}) =>
  paginate(paymentChannels, q.page, q.size, (x) => kwHit(q.keyword, x.channelCode, x.channelName, x.countries, x.currencies));
export const savePaymentChannel = (x: Partial<PaymentChannel>) =>
  upsert(paymentChannels, x, "channelCode", () => nextNo("CH", paymentChannels));

/** 今日基准（mock 时间轴的"现在"），发送记录页头统计据此判定"今日"。 */
const TODAY = iso(0).slice(0, 10);

/** 联系方式脱敏：手机保留前 6 后 2，邮箱保留首字母与域名。前端永不承载完整联系方式。 */
export function maskTarget(v: string): string {
  if (v.includes("@")) {
    const [name, domain] = v.split("@");
    return `${name.slice(0, 1)}***@${domain}`;
  }
  if (v.length <= 8) return `${v.slice(0, 3)}****`;
  return `${v.slice(0, 6)}****${v.slice(-2)}`;
}

// —— §9 发送记录 ——
const NOTIFY_SCENES = ["OTP 验证码", "借出成功", "归还成功", "扣费通知", "逾期提醒", "工单派单", "提现结果", "告警通知"];
const NOTIFY_FAILS = ["运营商拒收（号码停机）", "邮箱硬退信（地址不存在）", "设备 token 已失效", "触达拉黑名单命中", "上游限流，稍后重试"];
const NOTIFY_TARGETS = [
  "+9715012345678", "+9715098765432", "+966501234567", "+9715055512345",
  "fatima.a@example.ae", "omar.k@example.sa", "layla.h@example.ae",
  "dGtuX2FwbnNfODkwMTIz", "+201001234567", "+9715077788899",
];
/** 单条计费：Push 近乎免费，短信/WhatsApp 单价高——成本差异是发送记录页存在的理由。 */
const channelCost = (ch: NotifyLog["channel"]) =>
  ch === "PUSH" ? 0 : ch === "EMAIL" ? 0.01 : ch === "WHATSAPP" ? 0.11 : 0.09;

export const notifyLogs: NotifyLog[] = Array.from({ length: 42 }, (_, i) => {
  const channel = p(["SMS", "EMAIL", "PUSH", "WHATSAPP"] as const, i);
  const cost = channelCost(channel);
  const failed = i % 11 === 0;
  return {
    logNo: `NL${7000 + i}`,
    channel,
    templateNo: `NT${100 + (i % 14)}`,
    target: maskTarget(p(NOTIFY_TARGETS, i)),
    scene: p(NOTIFY_SCENES, i),
    // 前 16 条落在"今日"，其余往前铺 1~6 天，让页头统计有真实分母。
    sentAt: i < 16 ? iso(i * 1800_000) : iso((i - 15) * 86400_000 / 4 + 43200_000),
    status: failed ? "FAILED" : "SENT",
    failReason: failed ? p(NOTIFY_FAILS, i) : null,
    cost,
    currency: "AED",
    // 历史记录都是"原始发送"：重发只会在运营点按钮时新增，seed 里不预置重发链
    idempotencyKey: null,
    resendOf: null,
  } as NotifyLog;
});

export const listNotifyLogs = (q: PageQuery & { channel?: string; status?: string; sort?: string; dir?: string } = {}) => {
  const rows = notifyLogs.filter((x) =>
    (!q.channel || x.channel === q.channel) &&
    (!q.status || x.status === q.status) &&
    kwHit(q.keyword, x.logNo, x.templateNo, x.target, x.scene, x.failReason));
  if (q.sort) {
    const dir = q.dir === "asc" ? 1 : -1;
    rows.sort((a, b) => (q.sort === "cost" ? (a.cost - b.cost) : a.sentAt.localeCompare(b.sentAt)) * dir);
  }
  return paginate(rows, q.page, q.size);
};
/** 页头统计：今日发送量 / 失败率 / 今日成本（失败率按今日口径，避免历史稀释当日异常）。 */
export function getNotifyLogStats(): NotifyLogStats {
  const today = notifyLogs.filter((x) => x.sentAt.slice(0, 10) === TODAY);
  const failed = today.filter((x) => x.status === "FAILED").length;
  return {
    sentToday: today.length,
    failedToday: failed,
    failRate: today.length ? failed / today.length : 0,
    costToday: Math.round(today.reduce((s, x) => s + x.cost, 0) * 100) / 100,
    currency: "AED",
  };
}

// ============================================================================
// S7 · 模板预览 / 试发 · 发送记录重发（拍板 #6）
// ----------------------------------------------------------------------------
// 幂等：试发与重发都必须带 idempotencyKey，同键第二次直接拒绝。口径与 marketing.ts
// 的 sendPushMessage、cs.ts 的退款一致——重复提交＝真的多发一条、多扣一次钱。
// 键在**全部校验通过之后**才登记：校验失败就烧掉键，运营改完参数再点就永远发不出去。
// ============================================================================
export class NotifySendError extends Error {
  constructor(msg: string) { super(msg); this.name = "NotifySendError"; }
}

/** 已用过的幂等键（试发与重发共用一个命名空间：两者都是"对外真发一条"）。 */
const usedNotifyKeys = new Set<string>(
  notifyLogs.map((x) => x.idempotencyKey).filter((k): k is string => !!k),
);

/** 预览用示例值。缺项是有意的（PROMO 的 couponName 没有），用来暴露"变量没填就会带 {{}} 发出去"。 */
const PREVIEW_SAMPLES: Record<string, string> = {
  userName: "Fatima A.", siteName: "Dubai Mall L2", powerbankNo: "PB20007", freeMinutes: "5",
  hours: "3", amount: "12.00", currency: "AED", orderNo: "ORD500123", code: "8421", expireMin: "5",
  woNo: "WO40088", deadline: "今天 18:00", result: "到账", settleDays: "3",
  title: "斋月特惠", body: "借出满 30 分钟立减 5 AED",
};

const findTemplate = (templateNo: string) => {
  const t = notifyTemplates.find((x) => x.templateNo === templateNo);
  if (!t) throw new NotifySendError(`通知模板 ${templateNo} 不存在`);
  return t;
};
const declaredVars = (t: NotifyTemplate) => t.params.split(",").map((s) => s.trim()).filter(Boolean);

/** 预览：只渲染，不发送、不计费、不留痕。未提供且无示例值的变量原样留 {{x}} 并列进 missingVars。 */
export function previewNotifyTemplate(templateNo: string, vars: Record<string, string> = {}): NotifyTemplatePreview {
  const t = findTemplate(templateNo);
  const used: Record<string, string> = {};
  const missing: string[] = [];
  for (const name of declaredVars(t)) {
    const v = (vars[name] ?? "").trim() || PREVIEW_SAMPLES[name];
    if (v) used[name] = v; else missing.push(name);
  }
  const rendered = t.content.replace(/\{\{(\w+)\}\}/g, (whole, name: string) => used[name] ?? whole);
  return {
    templateNo: t.templateNo, channel: t.channel, lang: t.lang,
    // 邮件才有标题，其余渠道没有这个概念——给个空串而不是塞模板名，免得运营以为短信会带标题
    subject: t.channel === "EMAIL" ? `【ShareHub】${t.name}` : "",
    rendered, vars: used, missingVars: missing,
  };
}

/** 试发：真发一条并落一条发送记录（试发也计费，所以必须能在记录里对上账）。 */
export function testSendNotifyTemplate(templateNo: string, x: NotifyTestSendPayload): NotifyLog {
  const key = (x?.idempotencyKey ?? "").trim();
  if (!key) throw new NotifySendError("试发必须携带幂等键（idempotencyKey）——重复提交会真的多发一条");
  const t = findTemplate(templateNo);
  const target = (x?.target ?? "").trim();
  if (!target) throw new NotifySendError("试发目标不能为空（手机号或邮箱）");
  if (t.status !== "ENABLED") throw new NotifySendError(`模板 ${templateNo} 已停用，请先启用再试发`);
  const preview = previewNotifyTemplate(templateNo, x.vars);
  if (preview.missingVars.length) {
    throw new NotifySendError(`变量未填全：${preview.missingVars.join("、")}——带着 {{}} 发出去是事故`);
  }
  if (usedNotifyKeys.has(key)) throw new NotifySendError(`幂等键 ${key} 已提交过，拒绝重复发送`);
  usedNotifyKeys.add(key);

  const log: NotifyLog = {
    logNo: nextNo("NL", notifyLogs, 7000, "logNo"),
    channel: t.channel, templateNo: t.templateNo, target: maskTarget(target),
    scene: `试发 · ${t.scene}`, sentAt: iso(0), status: "SENT", failReason: null,
    cost: channelCost(t.channel), currency: "AED", idempotencyKey: key, resendOf: null,
  };
  notifyLogs.unshift(log);
  return log;
}

/**
 * 重发：**新增**一条记录并返回它，原记录一字不改（审计要看得见"发了两次"）。
 * 只允许重发失败记录：成功记录再发一遍就是重复骚扰 + 重复扣费，要补发请走模板试发。
 * 目标已在触达拉黑（且未到期）时拒绝——对退订用户重发既违规又白烧钱。
 */
export function resendNotifyLog(logNo: string, x: NotifyResendPayload): NotifyLog {
  const key = (x?.idempotencyKey ?? "").trim();
  if (!key) throw new NotifySendError("重发必须携带幂等键（idempotencyKey）——重复提交会重复扣费");
  const src = notifyLogs.find((l) => l.logNo === logNo);
  if (!src) throw new NotifySendError(`发送记录 ${logNo} 不存在`);
  if (src.status !== "FAILED") throw new NotifySendError(`记录 ${logNo} 是「已发送」，不允许重发——重复发送会重复扣费`);
  const blocked = notifyBlacklist.find((b) =>
    b.target === src.target && (b.channel === "ALL" || b.channel === src.channel) &&
    // 解除后不再拦：此前只看 expireAt，而解除现在走 status，漏了这一条
    // 就会出现「界面显示已解除、发送仍被拒」且两边都不报错。
    b.status === "ACTIVE" &&
    (!b.expireAt || new Date(b.expireAt).getTime() > Date.now()));
  if (blocked) throw new NotifySendError(`目标已在触达拉黑（${blocked.blockNo}），不允许重发`);
  if (usedNotifyKeys.has(key)) throw new NotifySendError(`幂等键 ${key} 已提交过，拒绝重复发送`);
  usedNotifyKeys.add(key);

  const log: NotifyLog = {
    ...src,
    logNo: nextNo("NL", notifyLogs, 7000, "logNo"),
    sentAt: iso(0), status: "SENT", failReason: null,
    idempotencyKey: key, resendOf: src.logNo,
  };
  notifyLogs.unshift(log);
  return log;
}

/**
 * S7 OpenAPI 密钥重置：只换掩码与重置时间。真实 AppSecret 由后端生成并带外交付一次，
 * **绝不**回传给前端——回传了就会进浏览器内存、日志与截图。
 */
export function resetOpenApiAppSecret(appNo: string): OpenApiApp {
  const i = openApiApps.findIndex((x) => x.appNo === appNo);
  if (i < 0) notFound("OpenAPI 应用", "OpenAPI app", appNo);
  // 掩码后四位跟着重置变化，否则运营看不出"到底换没换"
  const tail = String(1000 + (Date.now() % 9000));
  openApiApps[i] = { ...openApiApps[i], appSecretMasked: `sk_live_****${tail}`, secretResetAt: iso(0) };
  return openApiApps[i];
}

// —— §10 触达拉黑 ——
// 编号前缀 `NBL`（Notify BlackList）：`BL` 已归用户黑名单（user.ts 的 BL0001+）所有，
// 两套 `BL` 号并存时「按号搜索」会跨页搜出无关记录（台账 M10）。
/**
 * 手工解除过的（`status=RELEASED`）与解除人。其余为 ACTIVE。
 *
 * **三种状态都要有数据能走到**，否则界面区分不了这两件事的改动没法验：
 *   · 生效中 —— ACTIVE 且未到期（或永久）
 *   · 已到期 —— **仍是 ACTIVE**，只是 expireAt 落在过去（NBL905）；没人解除过它
 *   · 已解除 —— RELEASED + releasedBy；有人手工放开
 * 此前界面把后两者都显示成「已解除」，正是本次要修的。
 */
const BL_RELEASED: Record<string, string> = {
  NBL902: "Sara Ahmed",   // 硬退信后用户申诉，人工放开
  NBL907: "admin",
};

const BL_SEED: Omit<NotifyBlacklist, "status" | "releasedAt" | "releasedBy">[] = [
  { blockNo: "NBL901", target: maskTarget("+9715012345678"), channel: "SMS", reason: "USER_OPT_OUT", blockedAt: iso(3 * 86400_000), blockedBy: "系统（用户回复 STOP）", expireAt: null },
  { blockNo: "NBL902", target: maskTarget("omar.k@example.sa"), channel: "EMAIL", reason: "HARD_BOUNCE", blockedAt: iso(6 * 86400_000), blockedBy: "系统（SES 硬退信）", expireAt: null },
  { blockNo: "NBL903", target: maskTarget("+966501234567"), channel: "ALL", reason: "ABUSE", blockedAt: iso(9 * 86400_000), blockedBy: "风控值班组", expireAt: iso(-21 * 86400_000) },
  { blockNo: "NBL904", target: maskTarget("dGtuX2FwbnNfODkwMTIz"), channel: "PUSH", reason: "MANUAL", blockedAt: iso(12 * 86400_000), blockedBy: "客服中心", expireAt: iso(-3 * 86400_000) },
  { blockNo: "NBL905", target: maskTarget("+9715055512345"), channel: "SMS", reason: "ABUSE", blockedAt: iso(20 * 86400_000), blockedBy: "风控值班组", expireAt: iso(5 * 86400_000) },
  { blockNo: "NBL906", target: maskTarget("layla.h@example.ae"), channel: "EMAIL", reason: "USER_OPT_OUT", blockedAt: iso(26 * 86400_000), blockedBy: "系统（退订链接）", expireAt: null },
  { blockNo: "NBL907", target: maskTarget("+201001234567"), channel: "WHATSAPP", reason: "HARD_BOUNCE", blockedAt: iso(31 * 86400_000), blockedBy: "系统（WhatsApp 未注册）", expireAt: null },
  { blockNo: "NBL908", target: maskTarget("+9715077788899"), channel: "ALL", reason: "MANUAL", blockedAt: iso(40 * 86400_000), blockedBy: "运营中心", expireAt: iso(-60 * 86400_000) },
];
export const notifyBlacklist: NotifyBlacklist[] = BL_SEED.map((b, i) => {
  const by = BL_RELEASED[b.blockNo];
  return {
    ...b,
    status: (by ? "RELEASED" : "ACTIVE") as NotifyBlacklist["status"],
    // 解除时刻定在拉黑之后：放在拉黑之前的话，时间线自相矛盾而页面看不出来
    releasedAt: by ? iso(i * 3600_000) : null,
    releasedBy: by ?? null,
  };
});
export const listNotifyBlacklist = (q: PageQuery & { channel?: string; reason?: string } = {}) =>
  paginate(notifyBlacklist, q.page, q.size, (x) =>
    (!q.channel || x.channel === q.channel) &&
    (!q.reason || x.reason === q.reason) &&
    kwHit(q.keyword, x.blockNo, x.target, x.blockedBy));
export const saveNotifyBlacklist = (x: Partial<NotifyBlacklist>) =>
  upsert(notifyBlacklist, x, "blockNo", () => nextNo("NBL", notifyBlacklist));
/** 解除拉黑：软删除——把到期时间置为当下，保留拉黑历史供审计（决策 §八-4）。 */
export function releaseNotifyBlacklist(blockNo: string): NotifyBlacklist {
  const i = notifyBlacklist.findIndex((x) => x.blockNo === blockNo);
  if (i < 0) throw fail("拉黑记录不存在", "Blacklist entry not found", "سجل الحظر غير موجود");
  /*
   * **解除是软删 + 留痕**（后端 NotifyBlacklist 实体注释）：置 status=RELEASED
   * 并回填 releasedAt/releasedBy，**不动 expireAt**。
   * 此前这里把 expireAt 改成当下，等于把「手工解除」伪装成「刚好到期」——
   * 于是合规要问的「何时被谁放开」在数据里就不存在了。
   */
  notifyBlacklist[i] = {
    ...notifyBlacklist[i],
    status: "RELEASED",
    releasedAt: new Date().toISOString(),
    releasedBy: "admin",   // 真后端从会话取
  };
  return notifyBlacklist[i];
}

// —— §11 业务规则 ——
// ⚠️ 数值为 mock 占位，非业务口径；提现手续费率/封顶将来是提现审核页的唯一来源（规格 §17.1-3）。
export const bizRules: BizRules = {
  withdraw: { minAmount: 100, feeRate: 0.006, feeCap: 25, settleDays: 7, dailyLimit: 20000, needApproval: true },
  reservation: { maxDurationMin: 30, advanceHours: 24, holdFeePerMin: 0.2, maxConcurrent: 1 },
  billing: { freeMinutes: 5, unitMinutes: 30, capDaily: 20, buyoutPrice: 99, overdueHours: 72 },
  currency: "AED",
  updatedAt: iso(4 * 86400_000),
};
export const getBizRules = (): BizRules => bizRules;
/** 分区保存：只覆盖传入的分区，未传分区保持不变（三张 Card 各自保存）。 */
export function saveBizRules(x: Partial<BizRules>): BizRules {
  if (x.withdraw) bizRules.withdraw = { ...bizRules.withdraw, ...x.withdraw };
  if (x.reservation) bizRules.reservation = { ...bizRules.reservation, ...x.reservation };
  if (x.billing) bizRules.billing = { ...bizRules.billing, ...x.billing };
  bizRules.updatedAt = iso(0);
  return bizRules;
}

// —— §12 登录设置 ——
// `*` 是默认档（无专属配置的国家走它），列表置顶。
export const loginSettings: LoginSetting[] = [
  { country: "*", countryName: "默认（未单独配置的国家）", otpEnabled: true, passwordEnabled: false, appleEnabled: true, googleEnabled: true, otpExpireSec: 300, otpDailyLimit: 10, forceRealName: false },
  { country: "AE", countryName: "阿联酋", otpEnabled: true, passwordEnabled: false, appleEnabled: true, googleEnabled: true, otpExpireSec: 300, otpDailyLimit: 12, forceRealName: false },
  { country: "SA", countryName: "沙特", otpEnabled: true, passwordEnabled: true, appleEnabled: true, googleEnabled: false, otpExpireSec: 180, otpDailyLimit: 8, forceRealName: true },
  { country: "QA", countryName: "卡塔尔", otpEnabled: true, passwordEnabled: false, appleEnabled: true, googleEnabled: true, otpExpireSec: 300, otpDailyLimit: 10, forceRealName: false },
  { country: "KW", countryName: "科威特", otpEnabled: true, passwordEnabled: false, appleEnabled: false, googleEnabled: true, otpExpireSec: 300, otpDailyLimit: 10, forceRealName: false },
  { country: "EG", countryName: "埃及", otpEnabled: true, passwordEnabled: true, appleEnabled: false, googleEnabled: true, otpExpireSec: 600, otpDailyLimit: 6, forceRealName: false },
];
/** `*` 默认档恒置顶，其余按国家码排序——一眼看清"默认是什么、谁被单独放开"。 */
export const listLoginSettings = (q: PageQuery = {}) => {
  const rows = loginSettings
    .filter((x) => kwHit(q.keyword, x.country, x.countryName))
    .sort((a, b) => (a.country === "*" ? -1 : b.country === "*" ? 1 : a.country.localeCompare(b.country)));
  return paginate(rows, q.page, q.size);
};
export const saveLoginSetting = (x: Partial<LoginSetting>) =>
  upsert(loginSettings, x, "country", () => nextNo("XX", loginSettings, 0));

// —— §13 应用版本 ——
export const appVersions: AppVersion[] = [
  {
    versionId: "IOS-1.4.2", versionNo: "1.4.2", platform: "IOS", buildNo: 1420,
    releaseNote: "支持信用免押借出；修复 Dubai Mall 部分机柜扫码超时。",
    releaseNoteEn: "Credit-based deposit waiver; fixed scan timeout at some Dubai Mall cabinets.",
    releaseNoteAr: "الإعفاء من التأمين بناءً على التقييم الائتماني؛ إصلاح انتهاء مهلة المسح في بعض خزائن دبي مول.",
    forceUpdate: false, minSupported: "1.2.0", rolloutPercent: 100,
    downloadUrl: "https://apps.apple.com/app/id0000000000", status: "RELEASED", releasedAt: iso(6 * 86400_000),
  },
  {
    versionId: "IOS-1.5.0", versionNo: "1.5.0", platform: "IOS", buildNo: 1500,
    releaseNote: "新增预约取宝；阿语界面 RTL 全量适配。",
    releaseNoteEn: "Reserve-a-powerbank; full RTL polish for Arabic.",
    releaseNoteAr: "حجز بطارية مسبقًا؛ تحسين كامل لواجهة اللغة العربية من اليمين إلى اليسار.",
    forceUpdate: false, minSupported: "1.3.0", rolloutPercent: 20,
    downloadUrl: "https://apps.apple.com/app/id0000000000", status: "RELEASED", releasedAt: iso(1 * 86400_000),
  },
  {
    versionId: "ANDROID-1.4.2", versionNo: "1.4.2", platform: "ANDROID", buildNo: 1421,
    releaseNote: "支持信用免押借出；优化弱网下的归还确认。",
    releaseNoteEn: "Credit-based deposit waiver; better return confirmation on weak networks.",
    releaseNoteAr: "الإعفاء من التأمين؛ تحسين تأكيد الإرجاع عند ضعف الشبكة.",
    forceUpdate: false, minSupported: "1.2.0", rolloutPercent: 100,
    downloadUrl: "https://play.google.com/store/apps/details?id=example.sharehub", status: "RELEASED", releasedAt: iso(6 * 86400_000),
  },
  {
    versionId: "ANDROID-1.4.3", versionNo: "1.4.3", platform: "ANDROID", buildNo: 1430,
    releaseNote: "强制更新：修复支付回调丢单导致的重复扣费。",
    releaseNoteEn: "Mandatory update: fixes duplicate charges caused by lost payment callbacks.",
    releaseNoteAr: "تحديث إلزامي: إصلاح الخصم المزدوج الناتج عن فقدان استدعاء الدفع.",
    forceUpdate: true, minSupported: "1.4.3", rolloutPercent: 100,
    downloadUrl: "https://play.google.com/store/apps/details?id=example.sharehub", status: "RELEASED", releasedAt: iso(2 * 86400_000),
  },
  {
    versionId: "ANDROID-1.4.1", versionNo: "1.4.1", platform: "ANDROID", buildNo: 1410,
    releaseNote: "灰度中发现归还偶发失败，已回滚。",
    releaseNoteEn: "Rolled back: intermittent return failures found during rollout.",
    releaseNoteAr: "تم التراجع: أعطال متقطعة في الإرجاع أثناء الطرح التدريجي.",
    forceUpdate: false, minSupported: "1.2.0", rolloutPercent: 0,
    downloadUrl: "https://play.google.com/store/apps/details?id=example.sharehub", status: "ROLLBACK", releasedAt: iso(14 * 86400_000),
  },
  {
    versionId: "H5-2.1.0", versionNo: "2.1.0", platform: "H5", buildNo: 2100,
    releaseNote: "小程序/H5 免安装借还；接入 NEARPAY 快捷支付。",
    releaseNoteEn: "Install-free rental on H5; NEARPAY express checkout.",
    releaseNoteAr: "الاستئجار دون تثبيت عبر H5؛ الدفع السريع عبر NEARPAY.",
    forceUpdate: false, minSupported: "2.0.0", rolloutPercent: 100,
    downloadUrl: "https://h5.example.ae", status: "RELEASED", releasedAt: iso(9 * 86400_000),
  },
  {
    versionId: "H5-2.2.0", versionNo: "2.2.0", platform: "H5", buildNo: 2200,
    releaseNote: "草稿：站点地图与附近可借数量。",
    releaseNoteEn: "Draft: station map with live availability.",
    releaseNoteAr: "مسودة: خريطة المحطات مع توفر البطاريات مباشرة.",
    forceUpdate: false, minSupported: "2.0.0", rolloutPercent: 0,
    downloadUrl: "https://h5.example.ae", status: "DRAFT", releasedAt: null,
  },
];
/** 按平台分组显示：先平台（IOS→ANDROID→H5），组内按 buildNo 倒序（新版在上）。 */
const PLATFORM_ORDER: AppVersion["platform"][] = ["IOS", "ANDROID", "H5"];
export const listAppVersions = (q: PageQuery & { platform?: string } = {}) => {
  const rows = appVersions
    .filter((x) => (!q.platform || x.platform === q.platform) && kwHit(q.keyword, x.versionNo, x.platform, x.releaseNote, x.releaseNoteEn))
    .sort((a, b) =>
      PLATFORM_ORDER.indexOf(a.platform) - PLATFORM_ORDER.indexOf(b.platform) || b.buildNo - a.buildNo);
  return paginate(rows, q.page, q.size);
};
export const saveAppVersion = (x: Partial<AppVersion>) => {
  // versionId 由 平台-版本号 派生：同一版本号在不同平台是两条记录。
  const withId = x.versionId ? x : { ...x, versionId: `${x.platform ?? "IOS"}-${x.versionNo ?? "0.0.0"}` };
  // 规则与状态机在 mock 层强制（与运营管理页面的表单校验共用 lib/operation-rules）
  const prev = appVersions.find((v) => v.versionId === withId.versionId);
  const merged = { ...(prev ?? {}), ...withId } as AppVersion;
  const errors = validateAppVersion(merged, prev, appVersions);
  // 消息来自各自的 validate*（目前仍是单语中文，见 TDD R4 未尽项）；
  // 这里至少把类型对齐成 ApiError，页面据此区分业务拒绝与系统故障
  if (errors.length) throw new ApiError(400, errors[0]);
  // 发布时刻由系统记录，不信任客户端传值
  const releasedAt = merged.status === "RELEASED" && prev?.status !== "RELEASED" ? new Date().toISOString() : merged.releasedAt ?? null;
  return upsert(appVersions, { ...withId, releasedAt }, "versionId", () => `${x.platform ?? "IOS"}-${x.versionNo ?? "0.0.0"}`);
};
/** 回滚：置 ROLLBACK 且灰度归零（立即停止下发），保留记录不物理删。 */
export function rollbackAppVersion(versionId: string): AppVersion {
  const i = appVersions.findIndex((x) => x.versionId === versionId);
  if (i < 0) throw fail("版本不存在", "Version not found", "الإصدار غير موجود");
  if (appVersions[i].status !== "RELEASED") {
    throw fail("只有已发布的版本可以回滚", "Only a released version can be rolled back", "يمكن التراجع فقط عن إصدار منشور");
  }
  appVersions[i] = { ...appVersions[i], status: "ROLLBACK", rolloutPercent: 0 };
  return appVersions[i];
}

// —— §14 银行管理 ——
// IBAN 长度是各国固定值（AE 23 / SA 24 / EG 29 …），提现收款账户按此校验。
/**
 * 品牌（B1）。**必须有一个可用品牌** —— 建站点时「品牌」是必填，一个都没有就建不了站点。
 * 与后端 V50 的默认品牌同号（BR-DEFAULT），两边演示数据对得上。
 */
export const brands: Brand[] = [
  { brandNo: "BR-DEFAULT", name: "ShareHub", nameEn: "ShareHub", nameAr: "شير هب",
    logoUrl: "", supportPhone: "+97142000000", marketCode: "AE", status: "ENABLED", archivedAt: null },
  { brandNo: "BR002", name: "迪拜快充", nameEn: "Dubai QuickCharge", nameAr: "شحن دبي السريع",
    logoUrl: "", supportPhone: "+97142000111", marketCode: "AE", status: "ENABLED", archivedAt: null },
  { brandNo: "BR003", name: "旧品牌（已停用）", nameEn: "Legacy Brand", nameAr: "علامة قديمة",
    logoUrl: "", supportPhone: "", marketCode: null, status: "DISABLED", archivedAt: null },
];
export const listBrands = (q: PageQuery & { showArchived?: boolean } = {}) =>
  paginate(brands.filter((x) => (q.showArchived ? true : !x.archivedAt)), q.page, q.size,
    (x) => kwHit(q.keyword, x.brandNo, x.name, x.nameEn));
export const saveBrand = (x: Partial<Brand>) => {
  const name = (x.name ?? "").trim();
  if (!name) fail("品牌名称不能为空", "Brand name is required");
  // 同名品牌会让站点表单的下拉出现两个一模一样的选项，选错了看不出来
  const clash = brands.find((b) => b.name === name && b.brandNo !== x.brandNo);
  if (clash) fail(`品牌名「${name}」已被 ${clash.brandNo} 占用`, `Brand name "${name}" already exists`);
  return upsert(brands, x, "brandNo", () => nextNo("BR", brands));
};
export const archiveBrand = (no: string) => archiveRow(brands, "brandNo", no);
export const unarchiveBrand = (no: string) => unarchiveRow(brands, "brandNo", no);

export const banks: BankEntry[] = [
  { bankCode: "ENBD", bankName: "阿联酋国民银行", bankNameEn: "Emirates NBD", country: "AE", currency: "AED", swiftPrefix: "EBILAEAD", ibanLength: 23, status: "ENABLED", archivedAt: null },
  { bankCode: "FAB", bankName: "阿布扎比第一银行", bankNameEn: "First Abu Dhabi Bank", country: "AE", currency: "AED", swiftPrefix: "NBADAEAA", ibanLength: 23, status: "ENABLED", archivedAt: null },
  { bankCode: "ADCB", bankName: "阿布扎比商业银行", bankNameEn: "Abu Dhabi Commercial Bank", country: "AE", currency: "AED", swiftPrefix: "ADCBAEAA", ibanLength: 23, status: "ENABLED", archivedAt: null },
  { bankCode: "MASHREQ", bankName: "马士礼格银行", bankNameEn: "Mashreq Bank", country: "AE", currency: "AED", swiftPrefix: "BOMLAEAD", ibanLength: 23, status: "ENABLED", archivedAt: null },
  { bankCode: "DIB", bankName: "迪拜伊斯兰银行", bankNameEn: "Dubai Islamic Bank", country: "AE", currency: "AED", swiftPrefix: "DUIBAEAD", ibanLength: 23, status: "ENABLED", archivedAt: null },
  { bankCode: "RAJHI", bankName: "拉吉希银行", bankNameEn: "Al Rajhi Bank", country: "SA", currency: "SAR", swiftPrefix: "RJHISARI", ibanLength: 24, status: "ENABLED", archivedAt: null },
  { bankCode: "SNB", bankName: "沙特国民银行", bankNameEn: "Saudi National Bank", country: "SA", currency: "SAR", swiftPrefix: "NCBKSAJE", ibanLength: 24, status: "ENABLED", archivedAt: null },
  { bankCode: "RIYAD", bankName: "利雅得银行", bankNameEn: "Riyad Bank", country: "SA", currency: "SAR", swiftPrefix: "RIBLSARI", ibanLength: 24, status: "DISABLED", archivedAt: null },
  { bankCode: "QNB", bankName: "卡塔尔国民银行", bankNameEn: "Qatar National Bank", country: "QA", currency: "QAR", swiftPrefix: "QNBAQAQA", ibanLength: 29, status: "DISABLED", archivedAt: null },
  { bankCode: "NBK", bankName: "科威特国民银行", bankNameEn: "National Bank of Kuwait", country: "KW", currency: "KWD", swiftPrefix: "NBOKKWKW", ibanLength: 30, status: "DISABLED", archivedAt: null },
  { bankCode: "CIB", bankName: "埃及商业国际银行", bankNameEn: "Commercial International Bank", country: "EG", currency: "EGP", swiftPrefix: "CIBEEGCX", ibanLength: 29, status: "DISABLED", archivedAt: "2026-04-02T07:30:00Z" },
];
export const listBanks = (q: PageQuery & { country?: string; currency?: string; status?: string } = {}) =>
  paginate(banks, q.page, q.size, (x) =>
    liveHit(x, q.showArchived) &&
    (!q.country || x.country === q.country) &&
    (!q.status || x.status === q.status) &&
    (!q.currency || x.currency === q.currency) &&
    kwHit(q.keyword, x.bankCode, x.bankName, x.bankNameEn, x.swiftPrefix));
export const saveBank = (x: Partial<BankEntry>) => {
  // 代码类字段统一转大写再校验（旧页面文案承诺「自动转大写」）
  const up = (v?: string) => (v == null ? v : v.trim().toUpperCase());
  const norm = { ...x, bankCode: up(x.bankCode), country: up(x.country), currency: up(x.currency) };
  const prev = banks.find((b) => b.bankCode === norm.bankCode);
  // 编辑时调用方可能只传部分字段，按合并后的完整记录校验
  const errors = validateBank({ ...(prev ?? {}), ...norm }, prev, banks);
  // 消息来自各自的 validate*（目前仍是单语中文，见 TDD R4 未尽项）；
  // 这里至少把类型对齐成 ApiError，页面据此区分业务拒绝与系统故障
  if (errors.length) throw new ApiError(400, errors[0]);
  return upsert(banks, norm, "bankCode", () => nextNo("BK", banks));
};

// —— §15 问题管理 ——
// 编号前缀 `ISS`（Issue）：`PB` 已归充电宝（device.ts 的 PB20000+）所有，
// 原先问题管理占用 PB901+，与 `savePowerbank` 生成的号落在同一号段（台账 M9）。
export const problems: ProblemEntry[] = [
  {
    problemNo: "ISS901", category: "RENT",
    title: "扫码后充电宝没弹出", titleEn: "Nothing ejected after scanning", titleAr: "لم تخرج البطارية بعد مسح الرمز",
    answer: "请在 App 内点「重试弹出」；仍无反应说明卡槽卡宝，我们会自动开工单并在 30 分钟内到场，本单不计费。",
    answerEn: "Tap “Retry eject” in the app. If it still fails the slot is stuck — a work order is raised automatically, an engineer arrives within 30 minutes, and this rental is not charged.",
    answerAr: "اضغط «إعادة الإخراج» في التطبيق. إذا استمرت المشكلة فالفتحة عالقة — سيتم إنشاء طلب صيانة تلقائيًا والوصول خلال 30 دقيقة، ولن يتم احتساب رسوم.",
    suggestedAction: "TO_WORKORDER", sortNo: 1, status: "ENABLED", archivedAt: null,
  },
  {
    problemNo: "ISS902", category: "RETURN",
    title: "机柜满仓，还不进去", titleEn: "Cabinet is full, cannot return", titleAr: "الخزانة ممتلئة ولا يمكن الإرجاع",
    answer: "请在 App 地图上选择附近可还机柜（显示空仓数）；因满仓产生的超时时长会在申诉后免除。",
    answerEn: "Pick a nearby cabinet with free slots on the app map. Overdue time caused by a full cabinet is waived after you file a claim.",
    answerAr: "اختر خزانة قريبة بها فتحات فارغة من خريطة التطبيق. سيتم إعفاء وقت التأخير الناتج عن امتلاء الخزانة بعد تقديم الشكوى.",
    suggestedAction: "SELF_SERVICE", sortNo: 2, status: "ENABLED", archivedAt: null,
  },
  {
    problemNo: "ISS903", category: "BILLING",
    title: "已归还但仍在计费", titleEn: "Still being charged after returning", titleAr: "استمرار احتساب الرسوم بعد الإرجاع",
    answer: "归还回执以机柜上报为准，偶发延迟在 10 分钟内自动结算；超过 10 分钟请提交订单号，客服核对后按实际归还时间重算并退差额。",
    answerEn: "Return is confirmed by the cabinet report; occasional delays settle automatically within 10 minutes. Beyond that, submit the order number — we recalculate by the actual return time and refund the difference.",
    answerAr: "يتم تأكيد الإرجاع من تقرير الخزانة، وتتم التسوية تلقائيًا خلال 10 دقائق. بعد ذلك، أرسل رقم الطلب وسنعيد الحساب حسب وقت الإرجاع الفعلي ونرد الفرق.",
    suggestedAction: "TO_REFUND", sortNo: 3, status: "ENABLED", archivedAt: null,
  },
  {
    problemNo: "ISS904", category: "BILLING",
    title: "押金什么时候退", titleEn: "When is my deposit refunded", titleAr: "متى يتم رد مبلغ التأمين",
    answer: "归还后押金即时解冻，银行入账通常 1-3 个工作日（部分发卡行最长 7 天）。信用免押用户无押金冻结。",
    answerEn: "The deposit is released immediately after return; banks post it in 1-3 business days (up to 7 with some issuers). Credit-waiver users have no deposit hold.",
    answerAr: "يتم تحرير التأمين فور الإرجاع، ويستغرق ظهوره في البنك من 1 إلى 3 أيام عمل (حتى 7 أيام لدى بعض البنوك). لا يوجد تأمين لمستخدمي الإعفاء الائتماني.",
    suggestedAction: "SELF_SERVICE", sortNo: 4, status: "ENABLED", archivedAt: null,
  },
  {
    problemNo: "ISS905", category: "DEVICE",
    title: "充电宝充不进电 / 线坏了", titleEn: "Powerbank not charging or cable broken", titleAr: "البطارية لا تشحن أو الكابل تالف",
    answer: "请就近归还并在 App 内报障，本单免费；我们会锁定该充电宝编号并派维修回收。",
    answerEn: "Return it at the nearest cabinet and report the fault in the app — this rental is free. We lock that powerbank and dispatch a technician to collect it.",
    answerAr: "أعِد البطارية في أقرب خزانة وأبلغ عن العطل في التطبيق — هذا الاستئجار مجاني. سنقوم بحظر البطارية وإرسال فني لاستلامها.",
    suggestedAction: "TO_WORKORDER", sortNo: 5, status: "ENABLED", archivedAt: null,
  },
  {
    problemNo: "ISS906", category: "ACCOUNT",
    title: "收不到验证码", titleEn: "Not receiving the OTP", titleAr: "لا أستلم رمز التحقق",
    answer: "请确认号码所在国家已开放注册，并检查是否曾回复 STOP 退订（会进入触达拉黑）。可改用 Apple / Google 登录。",
    answerEn: "Check that your country is open for sign-up and whether you previously replied STOP (which adds you to the send-blocklist). You can also sign in with Apple or Google.",
    answerAr: "تأكد من أن بلدك متاح للتسجيل، وتحقق مما إذا كنت قد رددت بكلمة STOP سابقًا (تؤدي إلى الحظر). يمكنك أيضًا تسجيل الدخول عبر Apple أو Google.",
    suggestedAction: "TO_CS", sortNo: 6, status: "ENABLED", archivedAt: null,
  },
  {
    problemNo: "ISS907", category: "RENT",
    title: "同时借多个充电宝", titleEn: "Renting more than one powerbank", titleAr: "استئجار أكثر من بطارية",
    answer: "单账号默认同时可借 1 个；实名用户可申请提升至 2 个，超出请使用同行人账号。",
    answerEn: "One active rental per account by default; verified users can request a limit of two. Beyond that, please use a companion’s account.",
    answerAr: "استئجار واحد نشط لكل حساب افتراضيًا؛ يمكن للمستخدمين الموثقين طلب رفعه إلى اثنين. لما زاد عن ذلك، استخدم حساب مرافق.",
    suggestedAction: "SELF_SERVICE", sortNo: 7, status: "ENABLED", archivedAt: null,
  },
  {
    problemNo: "ISS908", category: "OTHER",
    title: "发票 / 报销凭证", titleEn: "Invoice for expense claims", titleAr: "الفاتورة الضريبية للمصروفات",
    answer: "在「我的-订单」选择订单申请电子发票，含 TRN 税号，通常 10 分钟内发送到邮箱。",
    answerEn: "Request an e-invoice from My Orders; it includes the TRN and usually arrives by email within 10 minutes.",
    answerAr: "اطلب الفاتورة الإلكترونية من «طلباتي»؛ تتضمن الرقم الضريبي وتصل عبر البريد خلال 10 دقائق عادةً.",
    suggestedAction: "SELF_SERVICE", sortNo: 8, status: "ENABLED", archivedAt: null,
  },
  {
    problemNo: "ISS909", category: "OTHER",
    title: "斋月营业时间（已停用）", titleEn: "Ramadan opening hours (retired)", titleAr: "ساعات العمل في رمضان (موقوف)",
    answer: "旧版斋月说明，已由公告替代，保留仅供历史工单参考。",
    answerEn: "Legacy Ramadan notice, superseded by announcements; kept for historical tickets only.",
    answerAr: "إشعار رمضان القديم، تم استبداله بالإعلانات؛ محفوظ للرجوع فقط.",
    suggestedAction: "TO_CS", sortNo: 9, status: "DISABLED", archivedAt: null,
  },
];
export const listProblems = (q: PageQuery & { category?: string; status?: string } = {}) => {
  const rows = problems
    .filter((x) =>
      liveHit(x, q.showArchived) &&
      (!q.category || x.category === q.category) &&
      (!q.status || x.status === q.status) &&
      kwHit(q.keyword, x.problemNo, x.title, x.titleEn, x.titleAr, x.answer))
    .sort((a, b) => a.sortNo - b.sortNo);
  return paginate(rows, q.page, q.size);
};
export const saveProblem = (x: Partial<ProblemEntry>) => upsert(problems, x, "problemNo", () => nextNo("ISS", problems));

// —— §16 税率与发票（阶段 3）——
// 税号一律占位（`****`）：合规资料不落前端 mock。
export const taxSettings: TaxSetting[] = [
  { country: "AE", countryName: "阿联酋", taxName: "VAT", ratePercent: 5, trn: "1000****00003", invoiceTitle: "ShareHub FZ-LLC", includedInPrice: true, effectiveFrom: "2026-01-01" },
  { country: "SA", countryName: "沙特", taxName: "ZATCA VAT", ratePercent: 15, trn: "3000****00003", invoiceTitle: "ShareHub Arabia LLC", includedInPrice: true, effectiveFrom: "2026-04-01" },
  { country: "QA", countryName: "卡塔尔", taxName: "VAT（待立法）", ratePercent: 0, trn: "-", invoiceTitle: "ShareHub Qatar", includedInPrice: false, effectiveFrom: "2027-01-01" },
  { country: "KW", countryName: "科威特", taxName: "VAT（待立法）", ratePercent: 0, trn: "-", invoiceTitle: "ShareHub Kuwait", includedInPrice: false, effectiveFrom: "2027-01-01" },
  { country: "EG", countryName: "埃及", taxName: "VAT", ratePercent: 14, trn: "200-***-456", invoiceTitle: "ShareHub Egypt LLC", includedInPrice: false, effectiveFrom: "2027-01-01" },
];
export const listTaxSettings = (q: PageQuery = {}) =>
  paginate(taxSettings, q.page, q.size, (x) => kwHit(q.keyword, x.country, x.countryName, x.taxName, x.invoiceTitle));
export const saveTaxSetting = (x: Partial<TaxSetting>) =>
  upsert(taxSettings, x, "country", () => nextNo("XX", taxSettings, 0));

// —— G1 软删除：银行 / 问题类型 ——
export const archiveBank = (code: string) => archiveRow(banks, "bankCode", code);
export const unarchiveBank = (code: string) => unarchiveRow(banks, "bankCode", code);
export const archiveProblem = (no: string) => archiveRow(problems, "problemNo", no);
export const unarchiveProblem = (no: string) => unarchiveRow(problems, "problemNo", no);
