// 营销域：优惠券 coupons / 活动 campaigns / 推送 pushMessages / 邀请 referrals /
// 广告位 adSlots · 广告计划 adCampaigns · 投放数据 adDeliveries / 公告 notices（三语）。
// 广告位挂载的机柜号引用 device.ts 的 cabinets。
import type {
  Coupon, Campaign, PushMessage, Referral, AdSlot, AdCampaign, AdDelivery, Notice, PageQuery,
} from "../../types";
import { NICKS, p, iso } from "./internal";
import { paginate, kwHit, upsert, nextNo, liveHit, archiveRow, unarchiveRow } from "./helpers";
import { cabNo } from "./device";

export const coupons: Coupon[] = Array.from({ length: 14 }, (_, i) => ({
  couponNo: `CP${800 + i}`, name: p(["新人立减", "满减券", "周末折扣", "会员专享"], i),
  type: i % 2 === 0 ? "CUT" : "DISCOUNT", value: i % 2 === 0 ? [3, 5, 10][i % 3] : [8, 9][i % 2],
  threshold: (i % 3) * 10, stock: 1000 + i * 100, issued: (i * 137) % 900, status: i % 6 === 0 ? "PAUSED" : "ACTIVE",
  archivedAt: null,
}));
export const saveCoupon = (c: Partial<Coupon>) => upsert(coupons, c, "couponNo", () => nextNo("CP", coupons));

export const campaigns: Campaign[] = Array.from({ length: 14 }, (_, i) => ({
  campaignNo: `CMP${800 + i}`, name: p(["新人首借免费", "满3送1", "周末半价", "斋月回馈", "邀请有礼", "会员日"], i),
  kind: p(["满减", "折扣", "赠券", "积分"], i), rule: p(["满10减3", "首单立减5", "第2小时免费", "邀请返5AED"], i),
  status: p(["DRAFT", "RUNNING", "RUNNING", "ENDED"] as const, i),
  startAt: iso((i + 3) * 86400_000), endAt: iso(-(i + 10) * 86400_000),
}));
export const pushMessages: PushMessage[] = Array.from({ length: 16 }, (_, i) => ({
  pushNo: `PM${900 + i}`, title: p(["借充电宝立享优惠", "您有一张券即将过期", "新点位上线通知", "斋月特惠开启"], i),
  channel: i % 3 === 0 ? "SUBSCRIBE" : "APP_PUSH", audience: p(["全部用户", "活跃用户", "沉睡用户", "白金会员"], i),
  sentCount: i % 4 === 0 ? 0 : 500 + (i * 337) % 20000, status: i % 4 === 0 ? "DRAFT" : "SENT",
  sentAt: iso(i * 86400_000),
}));
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
export const listPushMessages = (q: PageQuery = {}) => paginate(pushMessages, q.page, q.size, (x) => kwHit(q.keyword, x.pushNo, x.title, x.audience));
export const listReferrals = (q: PageQuery = {}) => paginate(referrals, q.page, q.size, (x) => kwHit(q.keyword, x.inviteNo, x.inviter, x.invitee));
export const listAdSlots = (q: PageQuery = {}) => paginate(adSlots, q.page, q.size, (x) => kwHit(q.keyword, x.slotNo, x.cabinetNo));
export const listAdCampaigns = (q: PageQuery = {}) => paginate(adCampaigns, q.page, q.size, (x) => kwHit(q.keyword, x.adNo, x.advertiser, x.creative));
export const listAdDeliveries = (q: PageQuery = {}) => paginate(adDeliveries, q.page, q.size, (x) => kwHit(q.keyword, x.deliveryNo, x.adNo, x.slotNo));

export const saveCampaign = (x: Partial<Campaign>) => upsert(campaigns, x, "campaignNo", () => nextNo("CMP", campaigns));
export const savePushMessage = (x: Partial<PushMessage>) => upsert(pushMessages, x, "pushNo", () => nextNo("PM", pushMessages));
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

// —— G1 软删除：优惠券 / 公告 ——
export const archiveCoupon = (no: string) => archiveRow(coupons, "couponNo", no);
export const unarchiveCoupon = (no: string) => unarchiveRow(coupons, "couponNo", no);
export const archiveNotice = (no: string) => archiveRow(notices, "noticeNo", no);
export const unarchiveNotice = (no: string) => unarchiveRow(notices, "noticeNo", no);
