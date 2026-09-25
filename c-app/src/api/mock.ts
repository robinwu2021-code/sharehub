// Mock 实现（McpApi）。全部走 mock/db 内存数据 + 模拟延迟。后端未就绪即可跑通全部 UI。
import * as db from "@/mock/db";
import type { McpApi, LoginParams, RentParams, PayParams, OrderQ, NearbyQ } from "./contract";
import type { ConsumerOrder, LogoffItem, UserCoupon, RechargeResult, CsTicket, ReportResult } from "@/types";
import { t } from "@/i18n";

/** 注销申请的 mock 状态。模块级而非 db 里：它是会话内的一次性流程，不是种子数据。 */
let mockLogoff: LogoffItem | null = null;
/** 后端存的是 `yyyy-MM-dd HH:mm:ss` 文本，mock 跟着它走，免得页面按两套格式解析。 */
const fmt = (d: Date) => d.toISOString().slice(0, 19).replace("T", " ");

/** 与后端一致的时间格式（yyyy-MM-dd HH:mm:ss）。mock 出 ISO 会让格式化函数在两种源下表现不同。 */
const fmtNow = () => new Date().toISOString().slice(0, 19).replace("T", " ");

export const mockApi: McpApi = {
  login: (p: LoginParams) =>
    db.delay({ token: `mock-c-${p.grantType}`, cUserNo: "CU-0001", isNew: false, tenantNo: "MAIN" }),
  sendOtp: () => db.delay({ cooldown: 60 }, 300),
  register: () => db.delay({ token: "mock-c-register", cUserNo: "CU-0001", isNew: true, tenantNo: "MAIN" }, 500),
  resetPassword: () => db.delay({ ok: true as const }, 400),
  // mock 没有服务端会话可吊销，但必须存在：缺这个方法，mock 模式点登出会直接 TypeError
  logout: () => db.delay(undefined as void, 100),
  getProfile: () => db.delay(db.profile),

  /*
   * 注销：mock 也要**有状态**，否则「申请 → 看到冷静期 → 撤销」这条路在 mock 下走不通，
   * 而这正是这个页面唯一值得点的东西。冷静期与后端默认一致（15 天）。
   */
  currentLogoff: () => db.delay(mockLogoff),
  applyLogoff: () => {
    if (mockLogoff && mockLogoff.status === "PENDING") {
      return Promise.reject(new Error("已有进行中的注销申请"));
    }
    const now = new Date();
    const until = new Date(now.getTime() + 15 * 86400_000);
    mockLogoff = {
      cUserNo: "CU-0001",
      requestedAt: fmt(now),
      coolingUntil: fmt(until),
      status: "PENDING",
      purgedAt: null,
    };
    return db.delay(mockLogoff, 420);
  },
  cancelLogoff: () => {
    if (!mockLogoff || mockLogoff.status !== "PENDING") {
      return Promise.reject(new Error("没有进行中的注销申请，无法撤销"));
    }
    mockLogoff = { ...mockLogoff, status: "CANCELLED" };
    return db.delay(mockLogoff, 380);
  },
  updateProfile: (p) => {
    Object.assign(db.profile, p);
    return db.delay(db.profile, 400);
  },
  listNotices: () => db.delay(db.notices),
  listMessages: (q = {}) =>
    db.delay(db.paginate(
      db.messages.filter((m) => (!q.type || m.type === q.type) && (q.read === undefined || m.read === q.read)),
      q.page, q.size)),
  markMessageRead: (messageNo: string) => {
    const m = db.messages.find((x) => x.messageNo === messageNo);
    if (!m) return Promise.reject(new Error("消息不存在"));
    // 真改 db：退出去再进来，红点不该又亮回来
    m.read = true;
    m.readAt = m.readAt ?? new Date().toISOString().slice(0, 19).replace("T", " ");
    return db.delay(m, 200);
  },
  checkVersion: () => db.delay(db.appVersion, 300),
  storeDetail: (siteNo: string) => db.delay(db.storeOf(siteNo)),

  listFavorites: () => db.delay(db.cabinets.filter((c) => db.favorites.has(c.siteNo))),  // 形状同找柜：门店卡片
  toggleFavorite: (siteNo: string) => {
    if (db.favorites.has(siteNo)) db.favorites.delete(siteNo);
    else db.favorites.add(siteNo);
    return db.delay({ favorite: db.favorites.has(siteNo) }, 300);
  },
  ongoingOrder: () => db.delay(db.orders.find((o) => o.status === "IN_USE" || o.status === "DISPENSING") ?? null),
  buyout: (orderNo: string) => {
    const o = db.orders.find((x) => x.orderNo === orderNo);
    if (o) {
      const now = fmtNow();
      o.status = "SETTLED";
      o.feeAmount = 99;
      o.fees = [{ type: "RENT", amount: 99 }];
      o.rentEndAt = now;
      // 列表投影不带 timeline（为 null），买断返的是详情投影，所以这里要把它补起来
      o.timeline = [...(o.timeline ?? []), { status: "SETTLED", at: now }];
    }
    return db.delay(o ?? db.orders[0], 450);
  },

  nearbyCabinets: (q: NearbyQ = {}) =>
    db.delay(
      db.cabinets.filter(
        (c) => db.kwHit(q.keyword, c.siteName, c.address) && (!q.returnable || c.availableReturn > 0),
      ),
    ),
  cabinetAvailability: (cabinetNo: string) =>
    db.delay(db.availability[cabinetNo] ?? { ...Object.values(db.availability)[0], cabinetNo, borrowable: false }),

  rentOrder: (p: RentParams) => {
    const cab = db.cabinets.find((c) => c.cabinetNo === p.cabinetNo) ?? db.cabinets[0];
    const now = fmtNow();
    const orderNo = `R${Math.floor(performance.now() * 1000)}`;
    // 用券在 mock 层也要**真的改 db**：不改的话重开一次券还在，而线上那张已经核销了。
    // 校验口径照后端 CouponUsePort.offerOf —— mock 放行、线上拒单是最难查的一类不一致。
    if (p.couponNo) {
      const c = db.coupons.find((x) => x.couponNo === p.couponNo);
      if (!c || c.status !== "UNUSED") throw new Error(t("borrow.couponUnusable"));
      c.status = "USED";
      c.usedOrderNo = orderNo;
    }
    const order: ConsumerOrder = {
      orderNo,
      cUserNo: "CU-0001",
      status: "IN_USE",
      cabinetNo: cab.cabinetNo,
      siteName: cab.siteName,
      returnCabinetNo: null,
      returnSiteName: null,
      powerbankNo: "PB-90001",
      locationName: cab.address,
      rentStartAt: now,
      rentEndAt: null,
      durationMin: 0,
      feeAmount: 0,
      currency: cab.currency,
      depositAmount: p.useFreeDeposit ? 0 : db.availability[cab.cabinetNo]?.depositAmount ?? 50,
      fees: [],
      timeline: [
        { status: "CREATED", at: now },
        { status: "DISPENSING", at: now },
        { status: "IN_USE", at: now },
      ],
    };
    db.orders.unshift(order);
    return db.delay(order, 480);
  },
  getOrder: (orderNo: string) => db.delay(db.orders.find((o) => o.orderNo === orderNo) ?? db.orders[0]),
  listOrders: (q: OrderQ = {}) =>
    db.delay(db.paginate(db.orders, q.page, q.size, (o) => db.kwHit(q.keyword, o.orderNo, o.siteName) && (!q.status || o.status === q.status))),

  depositFree: (_cabinetNo: string) => db.delay({ authNo: `AUTH${Math.floor(performance.now())}`, frozen: 100 }, 420),
  pay: (p: PayParams) => db.delay({ payNo: `PAY${Math.floor(performance.now())}`, status: "SUCCESS" as const, cashierParams: { scene: p.scene } }, 480),

  listFaq: (category?: string) =>
    db.delay(category ? db.faqs.filter((f) => f.category === category) : db.faqs),

  // 分流照后端的规矩来：出口由字典里的 suggestedAction 决定，不是前端挑的。
  // mock 里也走这条，否则 mock 下永远看不到「自助解答」和「转退款」两种出口。
  report: (p) => {
    const faq = db.faqs.find((f) => f.problemNo === p.problemNo);
    const action = faq ? faq.suggestedAction : "TO_CS"; // 未知问题兜底转人工，与后端一致
    const seq = db.tickets.length + 3;
    const ticketNo = "TK" + String(seq).padStart(6, "0");
    const at = new Date().toISOString().slice(0, 19).replace("T", " ");
    const woNo = action === "TO_WORKORDER" ? "WO" + String(seq).padStart(6, "0") : null;
    const refundNo = action === "TO_REFUND" ? "RF" + String(seq).padStart(6, "0") : null;
    const sessionNo = action === "TO_CS" ? "CS" + String(seq).padStart(6, "0") : null;
    const status = action === "SELF_SERVICE" ? "CLOSED" : "PROCESSING";
    db.tickets.unshift({
      ticketNo,
      userNo: db.profile.cUserNo,
      orderNo: p.orderNo ?? null,
      cabinetNo: p.cabinetNo ?? null,
      problemNo: p.problemNo,
      issue: p.issue,
      channel: p.channel ?? "APP",
      status: status as CsTicket["status"],
      handlerNo: null,
      woNo,
      refundNo,
      createdAt: at,
    });
    const r: ReportResult = { reportNo: ticketNo, status, suggestedAction: action, woNo, refundNo, sessionNo, createdAt: at };
    return db.delay(r, 400);
  },
  listReports: (q = {}) => db.delay(db.paginate(db.tickets.filter((t) => !q.status || t.status === q.status), q.page, q.size)),
  getReport: (reportNo: string) => db.delay(db.tickets.find((t) => t.ticketNo === reportNo) ?? null),

  getWallet: () => db.delay(db.wallet),
  listRechargePackages: () => db.delay(db.rechargePackages.filter((p) => p.status === "ENABLED")),
  recharge: (packageNo: string) => {
    const pkg = db.rechargePackages.find((p) => p.packageNo === packageNo && p.status === "ENABLED");
    if (!pkg) return Promise.reject(new Error("充值套餐不存在或已停用"));
    // 真改 db：余额与流水一起动，重开页面能读回。只改余额不记流水的话，
    // 「流水合计 === 余额」当场被破坏，而这正是真后端里最不该出的那类错。
    db.wallet.balance += pkg.payAmount;
    db.wallet.giftBalance += pkg.giftAmount;
    const rechargeNo = "RCH" + String(db.walletTxns.length + 100).padStart(6, "0");
    const at = new Date().toISOString().slice(0, 19).replace("T", " ");
    db.walletTxns.unshift({
      txnNo: "TX-" + (db.walletTxns.length + 1),
      type: "RECHARGE",
      direction: "IN",
      title: "钱包充值 " + pkg.name,
      amount: pkg.payAmount,
      currency: pkg.currency,
      bizType: "RECHARGE",
      bizNo: rechargeNo,
      createdAt: at,
    });
    if (pkg.giftAmount > 0) {
      db.walletTxns.unshift({
        txnNo: "TX-" + (db.walletTxns.length + 1),
        type: "BONUS",
        direction: "IN",
        title: "钱包充值 " + pkg.name,
        amount: pkg.giftAmount,
        currency: pkg.currency,
        bizType: "RECHARGE",
        bizNo: rechargeNo,
        createdAt: at,
      });
    }
    const r: RechargeResult = {
      rechargeNo,
      packageNo,
      payAmount: pkg.payAmount,
      giftAmount: pkg.giftAmount,
      creditAmount: pkg.payAmount + pkg.giftAmount,
      currency: pkg.currency,
      status: "PAID",
      balance: db.wallet.balance,
      bonus: db.wallet.giftBalance,
    };
    return db.delay(r, 500);
  },
  walletTxns: (q = {}) => db.delay(db.paginate(db.walletTxns, q.page, q.size)),
  listCoupons: () => db.delay(db.coupons),
  // claimed 现算而不是写死：领完之后再进页面，那一行必须已经是「已领取」。
  listClaimableCoupons: () =>
    db.delay(
      db.couponTpls.map((t) => ({
        ...t,
        claimed: db.coupons.some((c) => c.tplNo === t.tplNo && c.status === "UNUSED"),
      })),
    ),
  claimCoupon: (tplNo: string) => {
    const tpl = db.couponTpls.find((t) => t.tplNo === tplNo);
    if (!tpl) return Promise.reject(new Error("券模板不存在"));
    // 幂等，与后端一致：已有未使用券就返回那一张，不发第二张
    const exist = db.coupons.find((c) => c.tplNo === tplNo && c.status === "UNUSED");
    if (exist) return db.delay(exist, 300);
    if (tpl.remaining !== null && tpl.remaining <= 0) return Promise.reject(new Error("券已领完"));
    const got: UserCoupon = {
      couponNo: "CP" + String(db.coupons.length + 1).padStart(6, "0"),
      cUserNo: db.profile.cUserNo,
      tplNo: tpl.tplNo,
      tplName: tpl.name,
      tplType: tpl.type,
      value: tpl.value,
      threshold: tpl.threshold,
      currency: tpl.currency,
      status: "UNUSED",
      expireAt: "2026-12-31",
    };
    db.coupons.unshift(got); // 真改 db：重开页面能读回
    if (tpl.remaining !== null) tpl.remaining -= 1;
    return db.delay(got, 420);
  },
  listMemberships: () => db.delay(db.memberships),
};
