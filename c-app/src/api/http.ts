// 真实后端实现（McpApi）。端点严格对齐 docs/api/README §六/§七 的 /mp/**。
// 标注「待定」者需服务端 TDD + powerbank-common-api 契约补齐（C端功能清单 §八·4）。
import { client } from "./http-client";
import type { McpApi, LoginParams, RentParams, PayParams, PayResult, OrderQ, NearbyQ } from "./contract";
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
} from "@/types";

export const httpApi: McpApi = {
  login: (p: LoginParams) => client.post<LoginResult>("/mp/user/login", p),
  sendOtp: (p) => client.post<{ cooldown: number }>("/mp/user/otp/send", p),
  register: (p) => client.post<LoginResult>("/mp/user/register", p),
  resetPassword: (p) => client.post<{ ok: true }>("/mp/user/password/reset", p),
  getProfile: () => client.get<UserProfile>("/mp/user/profile"),

  listNotices: () => client.get<Notice[]>("/mp/notice"),

  nearbyCabinets: (q?: NearbyQ) => client.get<NearbyCabinet[]>("/mp/nearby/cabinets", q),
  cabinetAvailability: (cabinetNo: string) =>
    client.get<CabinetAvailability>(`/mp/nearby/cabinets/${cabinetNo}/availability`), // 待定
  storeDetail: (siteNo: string) => client.get<StoreDetail>(`/mp/sites/${siteNo}`),

  rentOrder: (p: RentParams) => client.post<RentOrder>("/mp/trade/orders/rent", p),
  getOrder: (orderNo: string) => client.get<RentOrder>(`/mp/trade/orders/${orderNo}`),
  listOrders: (q?: OrderQ) => client.get<PageResult<RentOrder>>("/mp/trade/orders", q),
  ongoingOrder: () => client.get<RentOrder | null>("/mp/trade/orders/ongoing"),
  buyout: (orderNo: string) => client.post<RentOrder>(`/mp/trade/orders/${orderNo}/buyout`),

  depositFree: (cabinetNo: string) => client.post<{ authNo: string; frozen: number }>("/mp/trade/deposit/free", { cabinetNo }),
  pay: (p: PayParams) => client.post<PayResult>("/mp/trade/pay", p),

  report: (p: ReportInput) => client.post<ReportResult>("/mp/user/report", p),

  getWallet: () => client.get<Wallet>("/mp/user/wallet"),
  listCoupons: () => client.get<Coupon[]>("/mp/user/coupons"),
  listMemberships: () => client.get<Membership[]>("/mp/user/membership"),
};
