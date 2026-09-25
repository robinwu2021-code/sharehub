// 全域 mock 数据集 + helper（内存）。MENA/迪拜场景、AED。对齐 ops-web mock/db 思路。
import type {
  NearbyCabinet,
  CabinetAvailability,
  ConsumerOrder,
  UserProfile,
  WalletOverview,
  UserCoupon,
  ClaimableCoupon,
  MembershipPlan,
  Notice,
  StoreDetail,
  WalletTxn,
  RechargePackage,
  FaqItem,
  CsTicket,
  MessageItem,
  AppVersionCheck,
} from "@/types";
import { CURRENCY, MOCK_DELAY_MS } from "@/shared/constants";

export const delay = <T>(v: T, ms = MOCK_DELAY_MS): Promise<T> =>
  new Promise((r) => setTimeout(() => r(v), ms));

export const kwHit = (kw: string | undefined, ...fields: (string | undefined | null)[]): boolean =>
  !kw || fields.some((f) => (f ?? "").toLowerCase().includes(kw.toLowerCase()));

export function paginate<T>(all: T[], page = 1, size = 10, pred?: (x: T) => boolean) {
  const filtered = pred ? all.filter(pred) : all;
  const start = (page - 1) * size;
  // 字段名必须与真后端一致（list 而非 records）。mock 跟着错的话，
  // mock 下一切正常、切真后端全空 —— 这正是 2026-09-23 之前的状态
  return { list: filtered.slice(start, start + size), total: filtered.length, page, size };
}

export const cabinets: NearbyCabinet[] = [
  { cabinetNo: "CAB-DXBM-01", siteNo: "ST-DXBM", siteName: "The Dubai Mall", address: "Downtown Dubai", distanceM: 240, lat: 25.1972, lng: 55.2796, availableBorrow: 6, availableReturn: 2, pricePerHour: 5, currency: CURRENCY, status: "ACTIVE" },
  { cabinetNo: "CAB-MOE-03", siteNo: "ST-MOE", siteName: "Mall of the Emirates", address: "Al Barsha", distanceM: 1300, lat: 25.1181, lng: 55.2003, availableBorrow: 3, availableReturn: 5, pricePerHour: 5, currency: CURRENCY, status: "ACTIVE" },
  { cabinetNo: "CAB-JBR-07", siteNo: "ST-JBR", siteName: "JBR The Walk", address: "Jumeirah Beach", distanceM: 2100, lat: 25.0785, lng: 55.1339, availableBorrow: 0, availableReturn: 8, pricePerHour: 6, currency: CURRENCY, status: "ACTIVE" },
  { cabinetNo: "CAB-DXB-T3", siteNo: "ST-DXB", siteName: "DXB Airport T3", address: "Dubai Intl Airport", distanceM: 5400, lat: 25.2528, lng: 55.3644, availableBorrow: 9, availableReturn: 1, pricePerHour: 8, currency: CURRENCY, status: "ACTIVE" },
];

export const availability: Record<string, CabinetAvailability> = Object.fromEntries(
  cabinets.map((c) => [
    c.cabinetNo,
    {
      cabinetNo: c.cabinetNo,
      siteName: c.siteName,
      borrowable: c.availableBorrow > 0,
      pricePerHour: c.pricePerHour,
      dailyCap: 30,
      buyoutPrice: 99,
      depositAmount: 50,
      freeQuota: 100,
      currency: CURRENCY,
    },
  ]),
);

export const orders: ConsumerOrder[] = [
  {
    orderNo: "R2026071200001",
    cUserNo: "CU-0001",
    status: "IN_USE",
    cabinetNo: "CAB-DXBM-01",
    siteName: "The Dubai Mall",
    returnCabinetNo: null,
    returnSiteName: null,
    powerbankNo: "PB-88213",
    locationName: "Downtown Dubai · 1F",
    rentStartAt: "2026-07-12 09:40:00",
    rentEndAt: null,
    durationMin: 72,
    feeAmount: 6,
    currency: CURRENCY,
    depositAmount: 0,
    fees: [{ type: "RENT", amount: 6 }],
    timeline: [
      { status: "CREATED", at: "2026-07-12 09:39:40" },
      { status: "DISPENSING", at: "2026-07-12 09:39:45" },
      { status: "IN_USE", at: "2026-07-12 09:40:00" },
    ],
  },
  {
    orderNo: "R2026071100042",
    cUserNo: "CU-0001",
    status: "SETTLED",
    cabinetNo: "CAB-MOE-03",
    siteName: "Mall of the Emirates",
    returnCabinetNo: "CAB-JBR-07",
    returnSiteName: "JBR The Walk",
    powerbankNo: "PB-12007",
    locationName: "Al Barsha · G 层",
    rentStartAt: "2026-07-11 14:10:00",
    rentEndAt: "2026-07-11 16:25:00",
    durationMin: 135,
    feeAmount: 15,
    currency: CURRENCY,
    depositAmount: 0,
    fees: [
      { type: "RENT", amount: 18 },
      { type: "WAIVE", amount: -3 },
    ],
    timeline: [
      { status: "IN_USE", at: "2026-07-11 14:10:00" },
      { status: "RETURNED", at: "2026-07-11 16:25:00" },
      { status: "SETTLED", at: "2026-07-11T16:25:10Z" },
    ],
  },
];

export const profile: UserProfile = {
  cUserNo: "CU-0001",
  nickname: "Ahmed",
  phone: "+971 50 *** 1234",
  email: "ahmed@example.com",
  creditScore: 720,
  freeDeposit: true,
  memberLevel: "Plus",
};

export const wallet: WalletOverview = { balance: 24, giftBalance: 10, depositAmount: 0, frozenAmount: 100, currency: CURRENCY };

// 收藏门店（siteNo 集合，可变）
export const favorites = new Set<string>(["ST-DXBM"]);

// 字段照 WalletTxnRow（createdAt 不是 at；bizType/bizNo 是来源单号）
export const walletTxns: WalletTxn[] = [
  { txnNo: "TX-06", type: "SPEND", direction: "OUT", title: "The Dubai Mall · rental", amount: -6, currency: CURRENCY, bizType: "ORDER", bizNo: "ORD000031", createdAt: "2026-07-12 11:00:00" },
  { txnNo: "TX-05", type: "BONUS", direction: "IN", title: "MembershipPlan bonus", amount: 10, currency: CURRENCY, bizType: "MEMBERSHIP", bizNo: "MB000002", createdAt: "2026-07-10 08:00:00" },
  { txnNo: "TX-04", type: "RECHARGE", direction: "IN", title: "Top-up", amount: 20, currency: CURRENCY, bizType: "RECHARGE", bizNo: "RCH000004", createdAt: "2026-07-09 19:20:00" },
  { txnNo: "TX-03", type: "REFUND", direction: "IN", title: "JBR The Walk · refund", amount: 4, currency: CURRENCY, bizType: "REFUND", bizNo: "RF000003", createdAt: "2026-07-06 14:05:00" },
  { txnNo: "TX-02", type: "SPEND", direction: "OUT", title: "Mall of the Emirates · rental", amount: -8, currency: CURRENCY, bizType: "ORDER", bizNo: "ORD000021", createdAt: "2026-07-04 17:40:00" },
  { txnNo: "TX-01", type: "RECHARGE", direction: "IN", title: "Top-up", amount: 10, currency: CURRENCY, bizType: "RECHARGE", bizNo: "RCH000001", createdAt: "2026-07-01 09:00:00" },
];

// 可购充值套餐。金额只在这里定义一份 —— mock 也不让端上传金额，
// 否则 mock 下能「充 1 到账 100」，切到真后端才发现被拒。
export const rechargePackages: RechargePackage[] = [
  { packageNo: "RP000001", name: "AED 20", payAmount: 20, giftAmount: 0, currency: CURRENCY, markets: "AE", validDays: null, sortNo: 1, status: "ENABLED", archivedAt: null },
  { packageNo: "RP000002", name: "AED 50 + 5", payAmount: 50, giftAmount: 5, currency: CURRENCY, markets: "AE", validDays: null, sortNo: 2, status: "ENABLED", archivedAt: null },
  { packageNo: "RP000003", name: "AED 100 + 15", payAmount: 100, giftAmount: 15, currency: CURRENCY, markets: "AE", validDays: 365, sortNo: 3, status: "ENABLED", archivedAt: null },
];

// 我的券包（已领到手的券实例）。字段照 UserCouponVO —— mock 与真后端同形，
// 否则切到真后端才发现页面读的是不存在的字段。
export const coupons: UserCoupon[] = [
  { couponNo: "CP000001", cUserNo: "CU-0001", tplNo: "CTPL-NEW", tplName: "New user AED 5 off", tplType: "CUT", value: 5, threshold: 0, currency: CURRENCY, status: "UNUSED", expireAt: "2026-08-01" },
  { couponNo: "CP000002", cUserNo: "CU-0001", tplNo: "CTPL-OVER10", tplName: "AED 3 off over 10", tplType: "CUT", value: 3, threshold: 10, currency: CURRENCY, status: "UNUSED", expireAt: "2026-07-20" },
  { couponNo: "CP000003", cUserNo: "CU-0001", tplNo: "CTPL-WEEKEND", tplName: "Weekend AED 2", tplType: "CUT", value: 2, threshold: 0, currency: CURRENCY, status: "USED", usedOrderNo: "ORD000031", expireAt: "2026-07-06" },
];

// 领券中心（可领的券模板）。前两个已在券包里 → claimed 由 mock 按 coupons 现算，不写死。
export const couponTpls: Omit<ClaimableCoupon, "claimed">[] = [
  { tplNo: "CTPL-NEW", name: "New user AED 5 off", type: "CUT", value: 5, threshold: 0, currency: CURRENCY, remaining: null },
  { tplNo: "CTPL-OVER10", name: "AED 3 off over 10", type: "CUT", value: 3, threshold: 10, currency: CURRENCY, remaining: 120 },
  { tplNo: "CTPL-SUMMER", name: "Summer AED 8 off over 30", type: "CUT", value: 8, threshold: 30, currency: CURRENCY, remaining: 7 },
  { tplNo: "CTPL-VIP", name: "VIP 20% off", type: "DISCOUNT", value: 0.8, threshold: 0, currency: CURRENCY, remaining: null },
];

// 问题字典（/mp/faq）。suggestedAction 决定报障提交后往哪走 ——
// 这里四条各覆盖一个出口，免得 mock 下永远只看得到「转人工」那一种。
export const faqs: FaqItem[] = [
  { problemNo: "ISS000001", category: "RENT", title: "充电宝没弹出来", answer: "请确认柜机指示灯是否常亮；30 秒内未弹出会自动退单。", suggestedAction: "TO_WORKORDER", sortNo: 1 },
  { problemNo: "ISS000002", category: "RETURN", title: "还不进去 / 柜机已满", answer: "可在地图上筛选「可还」的柜机，附近满柜时请就近改还。", suggestedAction: "TO_CS", sortNo: 2 },
  { problemNo: "ISS000003", category: "BILLING", title: "扣费比预期多", answer: "计费按小时取整，达每日封顶后不再计费。", suggestedAction: "TO_REFUND", sortNo: 3 },
  { problemNo: "ISS000004", category: "DEVICE", title: "充电宝充不上电", answer: "请更换一根线或换一个接口再试；仍不行可就近归还并重新借出。", suggestedAction: "SELF_SERVICE", sortNo: 4 },
];

// 我的报障（可变：提交报障会往里加）
export const tickets: CsTicket[] = [
  { ticketNo: "TK000002", userNo: "CU-0001", orderNo: "ORD000031", cabinetNo: "CAB1005", problemNo: "ISS000003", issue: "归还后还在计费", channel: "APP", status: "PROCESSING", handlerNo: null, woNo: null, refundNo: "RF000003", createdAt: "2026-09-20 10:12:00" },
  { ticketNo: "TK000001", userNo: "CU-0001", orderNo: "ORD000021", cabinetNo: "CAB1002", problemNo: "ISS000001", issue: "扫码后没弹出充电宝", channel: "APP", status: "CLOSED", handlerNo: "EMP0007", woNo: "WO000011", refundNo: null, createdAt: "2026-09-12 19:40:00" },
];

export const memberships: MembershipPlan[] = [
  { planNo: "MB-MONTH", name: "Monthly Pass", price: 29, benefits: ["First 2h free daily", "10% off", "Higher free-deposit"], active: true, expireAt: "2026-08-12" },
  { planNo: "MB-10", name: "10-Rides Card", price: 40, benefits: ["10 rides, 2h each"], active: false },
];

// 公告：三语三列全量下发，由端按当前语种取（与后端 NoticeVO 同形）
export const notices: Notice[] = [
  {
    noticeNo: "N-01", type: "OPERATION", pinned: true, status: "PUBLISHED",
    title: "斋月营业时间调整", titleEn: "Ramadan hours updated", titleAr: "تعديل ساعات العمل في رمضان",
    content: "部分商场点位斋月期间营业时间调整为 10:00–02:00。",
    contentEn: "Selected mall stations now operate 10:00–02:00 during Ramadan.",
    contentAr: "تعمل بعض المحطات في المراكز التجارية من 10:00 إلى 02:00 خلال رمضان.",
    startAt: "2026-07-01 00:00:00", endAt: null, publishedBy: "EMP0001",
    createdAt: "2026-07-12 09:00:00", archivedAt: null,
  },
  {
    noticeNo: "N-02", type: "OPERATION", pinned: false, status: "PUBLISHED",
    title: "迪拜机场 T3 新增点位", titleEn: "New stations at DXB T3", titleAr: "محطات جديدة في مطار دبي T3",
    content: "迪拜机场 T3 新增 9 台机柜，已可借可还。",
    contentEn: "9 new cabinets are now live at Dubai Airport Terminal 3.",
    contentAr: "‏9 خزائن جديدة متاحة الآن في المبنى 3 بمطار دبي.",
    startAt: "2026-07-08 00:00:00", endAt: null, publishedBy: "EMP0001",
    createdAt: "2026-07-08 12:00:00", archivedAt: null,
  },
];

// 站内信：每人一份、有已读态（公告没有这回事）
export const messages: MessageItem[] = [
  { messageNo: "MSG000003", type: "ORDER", title: "订单已结算", body: "订单 ORD000031 已结算，费用 AED 6.00。", read: false, readAt: null, createdAt: "2026-09-21 08:30:00" },
  { messageNo: "MSG000002", type: "REFUND", title: "退款已到账", body: "退款单 RF000003 已原路退回 AED 4.00，到账时间以发卡行为准。", read: false, readAt: null, createdAt: "2026-09-20 15:10:00" },
  { messageNo: "MSG000001", type: "MARKETING", title: "新人礼包已发放", body: "AED 5 无门槛券已放入你的券包，7 天内有效。", read: true, readAt: "2026-09-10 09:02:00", createdAt: "2026-09-10 09:00:00" },
];

export const appVersion: AppVersionCheck = {
  hasUpdate: true, versionNo: "1.1.0", buildNo: 110, forceUpdate: false,
  minSupported: "1.0.0", releaseNote: "修复归还偶发失败；地图加载更快。", downloadUrl: "https://example.com/app",
};

export function storeOf(siteNo: string): StoreDetail {
  const c = cabinets.find((x) => x.siteNo === siteNo) ?? cabinets[0];
  return {
    siteNo: c.siteNo,
    siteName: c.siteName,
    address: c.address,
    openHours: "10:00 – 24:00",
    lat: c.lat,
    lng: c.lng,
    distanceM: c.distanceM,
    availableBorrow: c.availableBorrow,
    availableReturn: c.availableReturn,
    pricePerHour: c.pricePerHour,
    currency: c.currency,
    favorite: favorites.has(c.siteNo),
    cabinets: [
      { cabinetNo: c.cabinetNo, borrow: c.availableBorrow, return: c.availableReturn },
      { cabinetNo: c.cabinetNo.replace(/\d+$/, "") + "09", borrow: Math.max(0, c.availableBorrow - 2), return: c.availableReturn + 1 },
    ],
  };
}
