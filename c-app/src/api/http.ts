// 真实后端实现（McpApi）。端点对齐 docs/api/README §六/§七 的 /mp/**。
// auth 路径由 ConsumerRentFlowTest 实测确认（/mp/auth/*），标「待定」者需服务端补齐。
import { client } from "./http-client";
import type { McpApi, LoginParams, RentParams, PayParams, PayResult, OrderQ, NearbyQ, PageQ, ProfilePatch } from "./contract";
import type {
  PageResult,
  NearbyCabinet,
  CabinetAvailability,
  ConsumerOrder,
  UserProfile,
  WalletOverview,
  UserCoupon,
  RechargePackage,
  RechargeResult,
  ClaimableCoupon,
  MembershipPlan,
  ReportInput,
  ReportResult,
  CsTicket,
  FaqItem,
  ConsumerLogin,
  Notice,
  MessageItem,
  AppVersionCheck,
  StoreDetail,
  WalletTxn,
  LogoffItem,
} from "@/types";

export const httpApi: McpApi = {
  login: (p: LoginParams) => client.post<ConsumerLogin>("/mp/auth/login", p),
  sendOtp: (p) => client.post<{ cooldown: number }>("/mp/auth/otp", p),
  register: (p) => client.post<ConsumerLogin>("/mp/auth/register", p),
  resetPassword: (p) => client.post<{ ok: true }>("/mp/auth/password/reset", p),
  logout: () => client.post<void>("/mp/auth/logout"),
  getProfile: () => client.get<UserProfile>("/mp/user/profile"),
  updateProfile: (p: ProfilePatch) => client.post<UserProfile>("/mp/user/profile", p), // 待定
  currentLogoff: () => client.get<LogoffItem | null>("/mp/user/logoff"),
  applyLogoff: () => client.post<LogoffItem>("/mp/user/logoff"),
  cancelLogoff: () => client.post<LogoffItem>("/mp/user/logoff/cancel"),

  listNotices: () => client.get<Notice[]>("/mp/notice"),
  listMessages: (q) => client.get<PageResult<MessageItem>>("/mp/user/messages", q),
  markMessageRead: (messageNo: string) => client.post<MessageItem>(`/mp/user/messages/${messageNo}/read`),
  checkVersion: (platform: string, lang?: string) =>
    client.get<AppVersionCheck>("/mp/app/version", { platform, lang }),

  nearbyCabinets: (q?: NearbyQ) => client.get<NearbyCabinet[]>("/mp/nearby/cabinets", q),
  cabinetAvailability: (cabinetNo: string) =>
    client.get<CabinetAvailability>(`/mp/nearby/cabinets/${cabinetNo}/availability`), // 待定
  storeDetail: (siteNo: string, at) => client.get<StoreDetail>(`/mp/sites/${siteNo}`, at),

  listFavorites: (at) => client.get<NearbyCabinet[]>("/mp/user/favorites", at),
  toggleFavorite: (siteNo: string) => client.post<{ favorite: boolean }>(`/mp/user/favorites/${siteNo}`), // 待定

  rentOrder: (p: RentParams) => client.post<ConsumerOrder>("/mp/trade/orders/rent", p),
  getOrder: (orderNo: string) => client.get<ConsumerOrder>(`/mp/trade/orders/${orderNo}`),
  listOrders: (q?: OrderQ) => client.get<PageResult<ConsumerOrder>>("/mp/trade/orders", q),
  ongoingOrder: () => client.get<ConsumerOrder | null>("/mp/trade/orders/ongoing"),
  buyout: (orderNo: string) => client.post<ConsumerOrder>(`/mp/trade/orders/${orderNo}/buyout`),

  depositFree: (cabinetNo: string) => client.post<{ authNo: string; frozen: number }>("/mp/trade/deposit/free", { cabinetNo }),
  pay: (p: PayParams) => client.post<PayResult>("/mp/trade/pay", p),

  listFaq: (category?: string) => client.get<FaqItem[]>("/mp/faq", { category }),
  report: (p: ReportInput) => client.post<ReportResult>("/mp/user/report", p),
  listReports: (q?: PageQ & { status?: string }) => client.get<PageResult<CsTicket>>("/mp/user/reports", q),
  getReport: (reportNo: string) => client.get<CsTicket | null>(`/mp/user/reports/${reportNo}`),

  getWallet: () => client.get<WalletOverview>("/mp/user/wallet"),
  listRechargePackages: () => client.get<RechargePackage[]>("/mp/user/recharge-packages"),
  recharge: (packageNo: string) => client.post<RechargeResult>("/mp/user/recharge", { packageNo }),
  walletTxns: (q?: PageQ) => client.get<PageResult<WalletTxn>>("/mp/user/wallet/txns", q), // 待定
  // 后端这个口回的是 PageResult 而不是数组。原来按数组取，拿到的是 {list,total} 对象，
  // v-for 照样能跑（遍历对象的值），于是券包里出现两行空卡片而不是报错。
  listCoupons: () => client.get<PageResult<UserCoupon>>("/mp/user/coupons", { size: 200 }).then((r) => r.list),
  listClaimableCoupons: () => client.get<ClaimableCoupon[]>("/mp/user/coupons/claimable"),
  claimCoupon: (tplNo: string) => client.post<UserCoupon>(`/mp/user/coupons/${tplNo}/claim`),
  listMemberships: () => client.get<MembershipPlan[]>("/mp/user/membership"),
};
