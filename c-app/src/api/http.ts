// 真实后端实现（McpApi）。端点对齐 docs/api/README §六/§七 的 /mp/**。
// auth 路径由 ConsumerRentFlowTest 实测确认（/mp/auth/*），标「待定」者需服务端补齐。
import { client } from "./http-client";
import type { McpApi, LoginParams, RentParams, PayParams, PayResult, OrderQ, NearbyQ, PageQ, ProfilePatch } from "./contract";
import type {
  PageResult,
  NearbyCabinet,
  CabinetAvailability,
  RentOrder,
  UserProfile,
  Wallet,
  Coupon,
  Membership,
  ReportInput,
  ReportResult,
  LoginResult,
  Notice,
  StoreDetail,
  WalletTxn,
  LogoffItem,
} from "@/types";

export const httpApi: McpApi = {
  login: (p: LoginParams) => client.post<LoginResult>("/mp/auth/login", p),
  sendOtp: (p) => client.post<{ cooldown: number }>("/mp/auth/otp", p),
  register: (p) => client.post<LoginResult>("/mp/auth/register", p),
  resetPassword: (p) => client.post<{ ok: true }>("/mp/auth/password/reset", p),
  logout: () => client.post<void>("/mp/auth/logout"),
  getProfile: () => client.get<UserProfile>("/mp/user/profile"),
  updateProfile: (p: ProfilePatch) => client.post<UserProfile>("/mp/user/profile", p), // 待定
  currentLogoff: () => client.get<LogoffItem | null>("/mp/user/logoff"),
  applyLogoff: () => client.post<LogoffItem>("/mp/user/logoff"),
  cancelLogoff: () => client.post<LogoffItem>("/mp/user/logoff/cancel"),

  listNotices: () => client.get<Notice[]>("/mp/notice"),

  nearbyCabinets: (q?: NearbyQ) => client.get<NearbyCabinet[]>("/mp/nearby/cabinets", q),
  cabinetAvailability: (cabinetNo: string) =>
    client.get<CabinetAvailability>(`/mp/nearby/cabinets/${cabinetNo}/availability`), // 待定
  storeDetail: (siteNo: string) => client.get<StoreDetail>(`/mp/sites/${siteNo}`),

  listFavorites: () => client.get<NearbyCabinet[]>("/mp/user/favorites"), // 待定
  toggleFavorite: (siteNo: string) => client.post<{ favorite: boolean }>(`/mp/user/favorites/${siteNo}`), // 待定

  rentOrder: (p: RentParams) => client.post<RentOrder>("/mp/trade/orders/rent", p),
  getOrder: (orderNo: string) => client.get<RentOrder>(`/mp/trade/orders/${orderNo}`),
  listOrders: (q?: OrderQ) => client.get<PageResult<RentOrder>>("/mp/trade/orders", q),
  ongoingOrder: () => client.get<RentOrder | null>("/mp/trade/orders/ongoing"),
  buyout: (orderNo: string) => client.post<RentOrder>(`/mp/trade/orders/${orderNo}/buyout`),

  depositFree: (cabinetNo: string) => client.post<{ authNo: string; frozen: number }>("/mp/trade/deposit/free", { cabinetNo }),
  pay: (p: PayParams) => client.post<PayResult>("/mp/trade/pay", p),

  report: (p: ReportInput) => client.post<ReportResult>("/mp/user/report", p),

  getWallet: () => client.get<Wallet>("/mp/user/wallet"),
  walletTxns: (q?: PageQ) => client.get<PageResult<WalletTxn>>("/mp/user/wallet/txns", q), // 待定
  listCoupons: () => client.get<Coupon[]>("/mp/user/coupons"),
  listMemberships: () => client.get<Membership[]>("/mp/user/membership"),
};
