// 营销域：优惠券 coupons / 活动 campaigns / 推送 pushMessages / 邀请 referrals /
// 广告位 adSlots · 广告计划 adCampaigns · 投放数据 adDeliveries / 公告 notices（三语）。
// 广告位挂载的机柜号引用 device.ts 的 cabinets。
import type {
  Coupon, Campaign, PushMessage, Referral, AdSlot, AdCampaign, AdDelivery, Notice, PageQuery,
  AudienceSpec, AudienceResolved, AudienceType, CouponIssueRecord, CouponIssuePayload,
  CouponIssueResult, PushAction, PushSendPayload,
} from "../../types";
import { PUSH_TRANSITIONS, canPushAction, couponRemaining, couponExpired } from "../../types";
import { NICKS, p, iso } from "./internal";
import { paginate, kwHit, upsert, nextNo, liveHit, archiveRow, unarchiveRow } from "./helpers";
import { cabNo } from "./device";
import { cUsers, members, consumerSegments } from "./user";

const now = () => new Date().toISOString();
/**
 * 有效期挂**真实当前时间**而不是 mock 固定时间轴（同 reservations 的处理）：
 * 「过期券不可发放」判定的是「expireAt 是否早于此刻」，用固定时间轴会随日历自然全部过期。
 */
const daysFromNow = (d: number) => new Date(Date.now() + d * 86400_000).toISOString();

export const coupons: Coupon[] = Array.from({ length: 14 }, (_, i) => ({
  couponNo: `CP${800 + i}`, name: p(["新人立减", "满减券", "周末折扣", "会员专享"], i),
  type: i % 2 === 0 ? "CUT" : "DISCOUNT", value: i % 2 === 0 ? [3, 5, 10][i % 3] : [8, 9][i % 2],
  threshold: (i % 3) * 10, stock: 1000 + i * 100, issued: (i * 137) % 900, status: i % 6 === 0 ? "PAUSED" : "ACTIVE",
  // i % 7 === 3 的几张刻意过期，用来演示 / 测试「过期券不可发放」
  expireAt: daysFromNow(i % 7 === 3 ? -(5 + i) : 60 + i * 10),
  archivedAt: null,
}));
export const saveCoupon = (c: Partial<Coupon>) => upsert(coupons, c, "couponNo", () => nextNo("CP", coupons));

export const campaigns: Campaign[] = Array.from({ length: 14 }, (_, i) => ({
  campaignNo: `CMP${800 + i}`, name: p(["新人首借免费", "满3送1", "周末半价", "斋月回馈", "邀请有礼", "会员日"], i),
  kind: p(["满减", "折扣", "赠券", "积分"], i), rule: p(["满10减3", "首单立减5", "第2小时免费", "邀请返5AED"], i),
  status: p(["DRAFT", "RUNNING", "RUNNING", "ENDED"] as const, i),
  startAt: iso((i + 3) * 86400_000), endAt: iso(-(i + 10) * 86400_000),
}));
// ============================================================================
// 营销投放人群（S2）：优惠券发放 / 推送触达共用
// ----------------------------------------------------------------------------
// 规模一律从既有主数据算出来（cUsers / members / consumerSegments），不写死数字。
// consumerSegments 是「分层画像」表，userCount 就是该层人数；members 是会员档案，
// 等级人数按档案条数算——两套口径都能在别的页面点开核对，不会出现「这里 3820、那里 12」。
// ============================================================================
export class AudienceError extends Error {
  constructor(msg: string) { super(msg); this.name = "AudienceError"; }
}

export const MEMBER_LEVELS = ["SILVER", "GOLD", "PLATINUM"] as const;
const LEVEL_LABEL: Record<string, string> = { SILVER: "白银会员", GOLD: "黄金会员", PLATINUM: "白金会员" };

/** 解析人群：返回可读口径 + 规模。维度非法 / 目标不存在一律抛错，不静默兜底成「全体」。 */
export function resolveAudience(spec: AudienceSpec): AudienceResolved {
  const type = spec.targetType;
  const value = (spec.targetValue ?? "").trim();
  if (type === "ALL") {
    return { targetType: type, targetDesc: `全体用户（${cUsers.length} 人）`, size: cUsers.length };
  }
  if (type === "MEMBER_LEVEL") {
    if (!MEMBER_LEVELS.includes(value as (typeof MEMBER_LEVELS)[number])) {
      throw new AudienceError(`会员等级不存在：「${value || "未选择"}」，可选 ${MEMBER_LEVELS.join(" / ")}`);
    }
    const size = members.filter((m) => m.level === value).length;
    return { targetType: type, targetDesc: `会员等级：${LEVEL_LABEL[value]}（${size} 人）`, size };
  }
  if (type === "SEGMENT") {
    const seg = consumerSegments.find((s) => s.segmentNo === value);
    if (!seg) throw new AudienceError(`消费者分层不存在：「${value || "未选择"}」`);
    return { targetType: type, targetDesc: `消费者分层：${seg.segment}（${seg.userCount} 人）`, size: seg.userCount };
  }
  if (type === "USER_LIST") {
    const nos = [...new Set(value.split(/[,，\s]+/).map((s) => s.trim()).filter(Boolean))];
    if (!nos.length) throw new AudienceError("请至少填写一个用户号");
    const bad = nos.filter((no) => !cUsers.some((u) => u.cUserNo === no));
    if (bad.length) throw new AudienceError(`用户号不存在：${bad.join("、")}——请填写 C 端真实用户号（如 U3000）`);
    return { targetType: type, targetDesc: `指定用户 ${nos.length} 人：${nos.slice(0, 5).join("、")}${nos.length > 5 ? " 等" : ""}`, size: nos.length };
  }
  throw new AudienceError(`发放对象类型不合法：「${String(type)}」`);
}

// —— 推送触达 ——
// 种子人群同样落在真实分层上（原先是「活跃用户」这类字符串，点开在任何页面都查无此群）。
const PUSH_SEED_AUDIENCE: AudienceSpec[] = [
  { targetType: "ALL", targetValue: "" },
  { targetType: "SEGMENT", targetValue: "SEG901" },
  { targetType: "MEMBER_LEVEL", targetValue: "PLATINUM" },
  { targetType: "SEGMENT", targetValue: "SEG905" },
];
const PUSH_CHANNELS: PushMessage["channel"][] = ["APP_PUSH", "SUBSCRIBE", "SMS"];
export const pushMessages: PushMessage[] = Array.from({ length: 16 }, (_, i) => {
  const aud = resolveAudience(p(PUSH_SEED_AUDIENCE, i));
  const draft = i % 4 === 0;
  // 成功率 ~94%（触达必有失败：关推送权限、停机、黑名单），successCount 恒 ≤ targetCount
  const success = draft ? 0 : Math.round(aud.size * 0.94);
  return {
    pushNo: `PM${900 + i}`, title: p(["借充电宝立享优惠", "您有一张券即将过期", "新点位上线通知", "斋月特惠开启"], i),
    content: p([
      "现在借充电宝，首单立减 3 AED，活动仅限本周。",
      "您账户内有一张优惠券将在 3 天后过期，记得使用。",
      "Marina Walk 新增 12 个机柜点位，扫码即可借还。",
      "斋月特惠开启：每日 20:00 后借出享 5 折。",
    ], i),
    channel: p(PUSH_CHANNELS, i), audience: aud.targetDesc,
    audienceType: aud.targetType, audienceValue: p(PUSH_SEED_AUDIENCE, i).targetValue ?? "",
    scheduledAt: null,
    targetCount: draft ? 0 : aud.size, successCount: success, sentCount: success,
    status: draft ? "DRAFT" : "SENT",
    sentAt: draft ? "" : iso(i * 86400_000),
    idempotencyKey: draft ? null : `PSH-PM${900 + i}-seed`,
    operatorName: draft ? null : p(["admin", "增长组", "运营中心"], i),
  };
});
export const referrals: Referral[] = Array.from({ length: 20 }, (_, i) => ({
  inviteNo: `RF${4000 + i}`, inviter: p(NICKS, i), invitee: p(NICKS, i + 3),
  reward: p([5, 8, 10], i), status: i % 3 === 0 ? "PENDING" : "REWARDED",
  createdAt: iso(i * 43200_000), currency: "AED",
}));
export const adSlots: AdSlot[] = Array.from({ length: 20 }, (_, i) => ({
  slotNo: `AS${600 + i}`, cabinetNo: cabNo(i), position: i % 2 === 0 ? "SCREEN" : "BODY",
  size: i % 2 === 0 ? p(["1080x1920", "720x1280"], i) : p(["A4贴片", "半身贴"], i),
  status: i % 3 === 0 ? "IDLE" : "OCCUPIED", createdAt: iso(i * 86400_000),
}));
export const adCampaigns: AdCampaign[] = Array.from({ length: 14 }, (_, i) => ({
  adNo: `AD${700 + i}`, advertiser: p(["Emirates NBD", "Careem", "Noon", "Talabat", "Etisalat"], i),
  creative: p(["品牌视频30s", "开屏图", "轮播图", "互动H5"], i), targeting: p(["全城", "机场点位", "商场点位", "白金会员"], i),
  status: p(["DRAFT", "RUNNING", "RUNNING", "ENDED"] as const, i),
  startAt: iso((i + 2) * 86400_000), endAt: iso(-(i + 12) * 86400_000),
}));
export const adDeliveries: AdDelivery[] = Array.from({ length: 24 }, (_, i) => ({
  deliveryNo: `DLV${5000 + i}`, adNo: `AD${700 + (i % 14)}`, slotNo: `AS${600 + (i % 20)}`,
  impressions: 1000 + (i * 733) % 50000, plays: 800 + (i * 511) % 40000,
  date: iso(i * 86400_000).slice(0, 10),
}));

export const listCampaigns = (q: PageQuery = {}) => paginate(campaigns, q.page, q.size, (x) => kwHit(q.keyword, x.campaignNo, x.name, x.kind));
export const listPushMessages = (q: PageQuery = {}) => paginate(pushMessages, q.page, q.size, (x) => kwHit(q.keyword, x.pushNo, x.title, x.content, x.audience));
export const listReferrals = (q: PageQuery = {}) => paginate(referrals, q.page, q.size, (x) => kwHit(q.keyword, x.inviteNo, x.inviter, x.invitee));
export const listAdSlots = (q: PageQuery = {}) => paginate(adSlots, q.page, q.size, (x) => kwHit(q.keyword, x.slotNo, x.cabinetNo));
export const listAdCampaigns = (q: PageQuery = {}) => paginate(adCampaigns, q.page, q.size, (x) => kwHit(q.keyword, x.adNo, x.advertiser, x.creative));
export const listAdDeliveries = (q: PageQuery = {}) => paginate(adDeliveries, q.page, q.size, (x) => kwHit(q.keyword, x.deliveryNo, x.adNo, x.slotNo));

export const saveCampaign = (x: Partial<Campaign>) => upsert(campaigns, x, "campaignNo", () => nextNo("CMP", campaigns));
export const saveAdSlot = (x: Partial<AdSlot>) => upsert(adSlots, x, "slotNo", () => nextNo("AS", adSlots));
export const saveAdCampaign = (x: Partial<AdCampaign>) => upsert(adCampaigns, x, "adNo", () => nextNo("AD", adCampaigns));

// ============================================================================
// 公告管理（营销域 · P1，对标简电云 E1）
// 三语（zh/en/ar）+ 生效期 + 置顶 —— 竞品公告只有单语，我们要覆盖 MENA 多语市场。
// ============================================================================
export const notices: Notice[] = [
  {
    noticeNo: "NTC900", title: "斋月期间机柜服务时间调整",
    titleEn: "Ramadan service hours update", titleAr: "تحديث ساعات الخدمة خلال رمضان",
    content: "斋月期间，Dubai Mall、Mall of Emirates 等商场点位服务至次日 02:00，归还不受影响。",
    contentEn: "During Ramadan, stations in Dubai Mall and Mall of Emirates stay open until 02:00. Returns are unaffected.",
    contentAr: "خلال رمضان، تعمل المحطات في دبي مول ومول الإمارات حتى الساعة 02:00. الإرجاع غير متأثر.",
    type: "SYSTEM", pinned: true, startAt: iso(3 * 86400_000), endAt: iso(-27 * 86400_000),
    status: "PUBLISHED", publishedBy: "运营中心", createdAt: iso(4 * 86400_000), archivedAt: null,
  },
  {
    noticeNo: "NTC901", title: "新用户首借 30 分钟免费",
    titleEn: "First rental free for 30 minutes", titleAr: "أول استئجار مجاني لمدة 30 دقيقة",
    content: "新用户首次借出充电宝，前 30 分钟免费，自动抵扣无需领券。",
    contentEn: "New users get the first 30 minutes free on their first power bank rental. No coupon needed.",
    contentAr: "يحصل المستخدمون الجدد على أول 30 دقيقة مجانًا عند أول استئجار لشاحن متنقل، دون الحاجة إلى قسيمة.",
    type: "PROMO", pinned: true, startAt: iso(10 * 86400_000), endAt: iso(-20 * 86400_000),
    status: "PUBLISHED", publishedBy: "增长组", createdAt: iso(11 * 86400_000), archivedAt: null,
  },
  {
    noticeNo: "NTC902", title: "DXB T3 航站楼点位夜间维护",
    titleEn: "Overnight maintenance at DXB Terminal 3", titleAr: "صيانة ليلية في مطار دبي المبنى 3",
    content: "本周四 01:00-04:00 对 DXB T3 全部机柜进行固件升级，期间暂停借出，已借订单正常计费与归还。",
    contentEn: "All cabinets at DXB T3 will receive a firmware upgrade on Thursday 01:00-04:00. Rentals pause; ongoing orders bill and return as usual.",
    contentAr: "سيتم تحديث البرامج الثابتة لجميع الخزائن في المبنى 3 بمطار دبي يوم الخميس من 01:00 إلى 04:00. يتوقف الاستئجار مؤقتًا.",
    type: "MAINTENANCE", pinned: false, startAt: iso(1 * 86400_000), endAt: iso(-2 * 86400_000),
    status: "PUBLISHED", publishedBy: "运维值班组", createdAt: iso(2 * 86400_000), archivedAt: null,
  },
  {
    noticeNo: "NTC903", title: "押金规则更新：信用免押上线",
    titleEn: "Deposit update: credit-based deposit waiver", titleAr: "تحديث التأمين: الإعفاء بناءً على التقييم الائتماني",
    content: "信用分达标用户借出充电宝免收 AED 50 押金，逾期未还仍按原规则计费买断。",
    contentEn: "Users above the credit threshold rent without the AED 50 deposit. Overdue buy-out rules remain unchanged.",
    contentAr: "يمكن للمستخدمين ذوي التقييم الائتماني المرتفع الاستئجار دون تأمين 50 درهمًا. تبقى قواعد الشراء عند التأخير كما هي.",
    type: "SYSTEM", pinned: false, startAt: iso(20 * 86400_000), endAt: iso(-40 * 86400_000),
    status: "PUBLISHED", publishedBy: "产品组", createdAt: iso(21 * 86400_000), archivedAt: null,
  },
  {
    noticeNo: "NTC904", title: "Marina Walk 新增 12 个机柜点位",
    titleEn: "12 new stations live at Marina Walk", titleAr: "تشغيل 12 محطة جديدة في مارينا ووك",
    content: "Marina Walk 沿线新增 12 个机柜，扫码即可借还，缓解晚间排队。",
    contentEn: "12 new cabinets are live along Marina Walk. Scan to rent or return and skip the evening queue.",
    contentAr: "تم تشغيل 12 خزانة جديدة على امتداد مارينا ووك. امسح الرمز للاستئجار أو الإرجاع.",
    type: "PROMO", pinned: false, startAt: iso(6 * 86400_000), endAt: iso(-24 * 86400_000),
    status: "PUBLISHED", publishedBy: "拓展组", createdAt: iso(7 * 86400_000), archivedAt: null,
  },
  {
    noticeNo: "NTC905", title: "支付通道切换公告",
    titleEn: "Payment channel migration notice", titleAr: "إشعار بتغيير قناة الدفع",
    content: "自本月起结算通道切换至 NEARPAY，账单主体显示为 ShareHub FZ-LLC，退款周期缩短至 3 个工作日。",
    contentEn: "Settlement moves to NEARPAY this month. Statements show ShareHub FZ-LLC and refunds now take 3 business days.",
    contentAr: "تنتقل التسوية إلى NEARPAY هذا الشهر. تظهر الفواتير باسم ShareHub FZ-LLC وتستغرق المبالغ المستردة 3 أيام عمل.",
    type: "SYSTEM", pinned: false, startAt: iso(15 * 86400_000), endAt: iso(-15 * 86400_000),
    status: "PUBLISHED", publishedBy: "财务中心", createdAt: iso(16 * 86400_000), archivedAt: null,
  },
  {
    noticeNo: "NTC906", title: "Yas Mall 点位临时停用（商场装修）",
    titleEn: "Yas Mall stations temporarily offline", titleAr: "إيقاف مؤقت لمحطات ياس مول",
    content: "因商场装修，Yas Mall B1 层 4 台机柜临时停用，请前往 L1 层机柜归还。",
    contentEn: "Due to mall renovation, 4 cabinets on Yas Mall B1 are offline. Please return at the L1 cabinets.",
    contentAr: "بسبب أعمال التجديد، تم إيقاف 4 خزائن في الطابق B1 بياس مول. يرجى الإرجاع في خزائن الطابق L1.",
    type: "MAINTENANCE", pinned: false, startAt: iso(-1 * 86400_000), endAt: iso(-30 * 86400_000),
    status: "DRAFT", publishedBy: "区域经理", createdAt: iso(1 * 86400_000), archivedAt: null,
  },
  {
    noticeNo: "NTC907", title: "国庆双周充电福利（已结束）",
    titleEn: "UAE National Day charging offer (ended)", titleAr: "عرض اليوم الوطني للإمارات (منتهي)",
    content: "国庆期间每日首单封顶 AED 3，活动已于上月结束，感谢参与。",
    contentEn: "During National Day the first daily rental was capped at AED 3. The campaign ended last month.",
    contentAr: "خلال اليوم الوطني، كان الحد الأقصى لأول استئجار يوميًا 3 دراهم. انتهى العرض الشهر الماضي.",
    type: "PROMO", pinned: false, startAt: iso(60 * 86400_000), endAt: iso(45 * 86400_000),
    status: "OFFLINE", publishedBy: "增长组", createdAt: iso(62 * 86400_000), archivedAt: null,
  },
  {
    noticeNo: "NTC908", title: "客服热线与 WhatsApp 支持时间",
    titleEn: "Support hotline and WhatsApp hours", titleAr: "أوقات الدعم عبر الهاتف وواتساب",
    content: "客服热线 09:00-23:00（GST），WhatsApp 全天留言，超时未归还请先在 App 内提交申诉。",
    contentEn: "Hotline 09:00-23:00 GST; WhatsApp accepts messages 24/7. For overdue returns, file a claim in the app first.",
    contentAr: "الخط الساخن من 09:00 إلى 23:00 بتوقيت الخليج، وواتساب متاح على مدار الساعة. للإرجاع المتأخر، قدّم شكوى عبر التطبيق.",
    type: "SYSTEM", pinned: false, startAt: iso(30 * 86400_000), endAt: iso(-60 * 86400_000),
    status: "PUBLISHED", publishedBy: "客服中心", createdAt: iso(31 * 86400_000), archivedAt: null,
  },
];

export const listNotices = (q: PageQuery = {}) =>
  paginate(notices, q.page, q.size, (x) => liveHit(x, q.showArchived) && kwHit(q.keyword, x.noticeNo, x.title, x.titleEn, x.titleAr, x.publishedBy));
export const saveNotice = (x: Partial<Notice>) => upsert(notices, x, "noticeNo", () => nextNo("NTC", notices));

// ============================================================================
// S2 · 优惠券发放（权限码 marketing:coupon:issue）
// ----------------------------------------------------------------------------
// 库存口径：`stock` 是**发行总量**（不变），`issued` 是**已发放数**（只增），
// 剩余 = stock - issued。发放不改 stock——否则「已发/库存」两个数会一起漂，
// 事后查不出这张券一共印了多少。四道闸门任一不过整批拒绝，不做半成功。
// ============================================================================
export class CouponIssueError extends Error {
  constructor(msg: string) { super(msg); this.name = "CouponIssueError"; }
}

export const couponIssueRecords: CouponIssueRecord[] = [
  {
    issueNo: "CIS900", couponNo: "CP801", couponName: "满减券",
    targetType: "SEGMENT", targetDesc: "消费者分层：高频通勤用户（3820 人）",
    quantity: 500, operatorName: "增长组", createdAt: iso(2 * 86400_000),
  },
  {
    issueNo: "CIS901", couponNo: "CP803", couponName: "会员专享",
    targetType: "MEMBER_LEVEL", targetDesc: "会员等级：白金会员（8 人）",
    quantity: 120, operatorName: "运营中心", createdAt: iso(5 * 86400_000),
  },
  {
    issueNo: "CIS902", couponNo: "CP804", couponName: "新人立减",
    targetType: "ALL", targetDesc: "全体用户（60 人）",
    quantity: 60, operatorName: "admin", createdAt: iso(9 * 86400_000),
  },
];

export const listCouponIssueRecords = (q: PageQuery & { couponNo?: string; targetType?: string } = {}) =>
  paginate(couponIssueRecords, q.page, q.size, (x) =>
    (!q.couponNo || x.couponNo === q.couponNo) &&
    (!q.targetType || x.targetType === q.targetType) &&
    kwHit(q.keyword, x.issueNo, x.couponNo, x.couponName, x.targetDesc, x.operatorName));

/**
 * 发放优惠券。闸门：
 *  ① 券必须存在且未归档；② 已下线（PAUSED）/ 已过期一律拒绝；
 *  ③ 张数为正整数；④ **不得超过剩余库存**（超发＝凭空印券）。
 * 通过后：`issued += quantity`（剩余随之减少）+ 落一条发放流水。
 */
export function issueCoupon(couponNo: string, x: CouponIssuePayload): CouponIssueResult {
  const c = coupons.find((y) => y.couponNo === couponNo);
  if (!c) throw new CouponIssueError(`优惠券 ${couponNo} 不存在`);
  if (c.archivedAt) throw new CouponIssueError(`优惠券 ${couponNo} 已归档，不可发放——请先恢复`);
  if (c.status !== "ACTIVE") throw new CouponIssueError(`优惠券 ${couponNo} 已下线（暂停），不可发放`);
  if (couponExpired(c)) throw new CouponIssueError(`优惠券 ${couponNo} 已于 ${c.expireAt.slice(0, 10)} 过期，不可发放`);

  const qty = Number(x?.quantity ?? 0);
  if (!Number.isInteger(qty) || qty <= 0) throw new CouponIssueError("发放张数必须是大于 0 的整数");
  const remaining = couponRemaining(c);
  if (qty > remaining) {
    throw new CouponIssueError(
      `发放张数 ${qty} 超过剩余库存 ${remaining}（发行总量 ${c.stock}、已发放 ${c.issued}）——超发等于凭空印券，请先调高发行总量`,
    );
  }

  const aud = resolveAudience(x); // 人群非法在这里抛 AudienceError
  const record: CouponIssueRecord = {
    issueNo: nextNo("CIS", couponIssueRecords, 900, "issueNo"),
    couponNo: c.couponNo, couponName: c.name,
    targetType: aud.targetType, targetDesc: aud.targetDesc,
    quantity: qty, operatorName: x.operatorName?.trim() || "admin", createdAt: now(),
  };
  c.issued += qty;
  couponIssueRecords.unshift(record);
  return { coupon: c, record };
}

// ============================================================================
// S2 · 推送触达发送（权限码 marketing:push:send）
// ----------------------------------------------------------------------------
// 状态机在本层强制：DRAFT →（定时）SCHEDULED → SENDING → SENT，SENT 是终态。
// 幂等：发送/重发必须带 idempotencyKey，同键第二次直接拒绝——触达是批量对外动作，
// 重复提交＝真的把消息发两遍（口径同退款，见 cs.ts applyRefund）。
// ============================================================================
export class PushError extends Error {
  constructor(msg: string) { super(msg); this.name = "PushError"; }
}

/** 已用过的幂等键（跨草稿全局唯一，防同一批内容换个推送号重发一遍）。 */
const usedPushKeys = new Set<string>(
  pushMessages.map((x) => x.idempotencyKey).filter((k): k is string => !!k),
);

const findPush = (pushNo: string) => {
  const x = pushMessages.find((y) => y.pushNo === pushNo);
  if (!x) throw new PushError(`推送 ${pushNo} 不存在`);
  return x;
};

/** 统一迁移入口：所有推送状态变更都走这里，禁止别处直接写 `p.status = ...`。 */
export function transitionPush(pushNo: string, action: PushAction, patch: Partial<PushMessage> = {}): PushMessage {
  const x = findPush(pushNo);
  if (!canPushAction(x.status, action)) {
    throw new PushError(`推送 ${pushNo} 当前状态「${x.status}」不允许执行「${PUSH_TRANSITIONS[action].label}」`);
  }
  Object.assign(x, patch, { status: PUSH_TRANSITIONS[action].to });
  return x;
}

/**
 * 新增 / 编辑推送草稿。**状态与发送结果字段一律由状态机写**，这里强制剥离，
 * 否则页面塞一个 `status: "SENT"` 就能绕过发送流程伪造已发送。
 */
export function savePushMessage(x: Partial<PushMessage>): PushMessage {
  const { status: _s, sentAt: _a, targetCount: _t, successCount: _c, sentCount: _n,
    idempotencyKey: _k, ...safe } = x;
  const aud = resolveAudience({
    targetType: (safe.audienceType ?? "ALL") as AudienceType,
    targetValue: safe.audienceValue ?? "",
  });
  const draft: Partial<PushMessage> = {
    ...safe, audienceType: aud.targetType, audienceValue: safe.audienceValue ?? "", audience: aud.targetDesc,
  };
  const existing = safe.pushNo && pushMessages.some((y) => y.pushNo === safe.pushNo);
  if (existing) return upsert(pushMessages, draft, "pushNo", () => nextNo("PM", pushMessages));
  return upsert(pushMessages, {
    content: "", channel: "APP_PUSH", scheduledAt: null,
    targetCount: 0, successCount: 0, sentCount: 0, status: "DRAFT", sentAt: "",
    idempotencyKey: null, operatorName: null, ...draft,
  }, "pushNo", () => nextNo("PM", pushMessages));
}

/**
 * 发送推送。定时（带 scheduledAt）→ SCHEDULED；立即 → SENDING → SENT 并落
 * sentAt / targetCount / successCount。已发送的券不会走到这里——SENT 不在 send.from 里。
 */
export function sendPushMessage(pushNo: string, x: PushSendPayload): PushMessage {
  const key = (x?.idempotencyKey ?? "").trim();
  if (!key) throw new PushError("发送必须携带幂等键（idempotencyKey）——重复提交会把消息真发两遍");
  if (usedPushKeys.has(key)) throw new PushError(`幂等键 ${key} 已提交过，拒绝重复发送`);

  const target = findPush(pushNo);
  if (!target.title?.trim() || !target.content?.trim()) throw new PushError("推送标题与内容不能为空");
  // 状态非法时先抛，**不能**在此之前占用幂等键（否则一次失败把键烧掉，用户再也发不出去）
  if (!canPushAction(target.status, "send")) {
    throw new PushError(`推送 ${pushNo} 当前状态「${target.status}」不允许执行「发送」`);
  }
  const aud = resolveAudience({ targetType: target.audienceType, targetValue: target.audienceValue });
  const operatorName = x.operatorName?.trim() || "admin";
  usedPushKeys.add(key);

  const scheduledAt = x.scheduledAt?.trim() || null;
  if (scheduledAt) {
    return transitionPush(pushNo, "schedule", {
      scheduledAt, audience: aud.targetDesc, targetCount: aud.size,
      successCount: 0, sentCount: 0, idempotencyKey: key, operatorName,
    });
  }
  transitionPush(pushNo, "send", {
    scheduledAt: null, audience: aud.targetDesc, targetCount: aud.size,
    idempotencyKey: key, operatorName,
  });
  // 成功率 ~94%：关推送权限 / 停机 / 触达黑名单必然吃掉一部分，successCount 恒 ≤ targetCount
  const success = Math.round(aud.size * 0.94);
  return transitionPush(pushNo, "finish", { sentAt: now(), successCount: success, sentCount: success });
}

// —— G1 软删除：优惠券 / 公告 ——
export const archiveCoupon = (no: string) => archiveRow(coupons, "couponNo", no);
export const unarchiveCoupon = (no: string) => unarchiveRow(coupons, "couponNo", no);
export const archiveNotice = (no: string) => archiveRow(notices, "noticeNo", no);
export const unarchiveNotice = (no: string) => unarchiveRow(notices, "noticeNo", no);
