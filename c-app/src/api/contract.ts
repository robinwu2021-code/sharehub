// 单一 API 契约（McpApi）。mock 与真实后端各实现一份（mock.ts / http.ts），
// 页面只依赖此接口，切换靠 index.ts 一处开关（消除散落的 if(USE_MOCK)）。对齐 ops-web 模式。
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
} from "@/types";

export interface PageQ {
  page?: number;
  size?: number;
  keyword?: string;
}
export type OrderQ = PageQ & { status?: string };
export type NearbyQ = { lat?: number; lng?: number; keyword?: string; returnable?: boolean };
// 资料可改字段（后端仅接受这几项，phone 走换绑单独流程）
export type ProfilePatch = Partial<Pick<UserProfile, "nickname" | "avatar" | "email">>;

// 登录换身份：因端而异的只有 grantType（App 手机 OTP/密码/Apple/Google，小程序微信）
export type GrantType = "phone_otp" | "password" | "apple" | "google" | "wechat_miniapp";
export interface LoginParams {
  grantType: GrantType;
  countryCode?: string; // +971（全球版区号）
  phone?: string;
  otp?: string;
  password?: string;
  code?: string; // 小程序 wx.login code / OAuth code
}
export interface OtpParams {
  countryCode: string;
  phone: string;
  scene?: "login" | "register" | "reset";
}
export interface RegisterParams {
  countryCode: string;
  phone: string;
  otp: string;
  nickname: string;
  email?: string;
  password?: string;
}
export interface ResetPwdParams {
  countryCode: string;
  phone: string;
  otp: string;
  password: string;
}

export interface RentParams {
  cabinetNo: string;
  useFreeDeposit: boolean; // true=免押预授权，false=降级押金
  couponNo?: string;
}
export type PayScene = "rent" | "deposit" | "recharge" | "membership";
export interface PayParams {
  scene: PayScene;
  orderNo?: string;
  amount: number;
}
export interface PayResult {
  payNo: string;
  status: "SUCCESS" | "PENDING" | "FAILED";
  cashierParams?: Record<string, unknown>; // 收银台唤起参数（真实经 nearpay）
}

export interface McpApi {
  // 认证（换 C 池 Bearer，之后属主鉴权）
  login(p: LoginParams): Promise<LoginResult>;
  sendOtp(p: OtpParams): Promise<{ cooldown: number }>;
  register(p: RegisterParams): Promise<LoginResult>;
  resetPassword(p: ResetPwdParams): Promise<{ ok: true }>;
  getProfile(): Promise<UserProfile>;
  updateProfile(p: ProfilePatch): Promise<UserProfile>;
  // 公告
  listNotices(): Promise<Notice[]>;
  // 找柜与地图
  nearbyCabinets(q?: NearbyQ): Promise<NearbyCabinet[]>;
  cabinetAvailability(cabinetNo: string): Promise<CabinetAvailability>;
  storeDetail(siteNo: string): Promise<StoreDetail>;
  // 收藏门店
  listFavorites(): Promise<NearbyCabinet[]>;
  toggleFavorite(siteNo: string): Promise<{ favorite: boolean }>;
  // 借还
  rentOrder(p: RentParams): Promise<RentOrder>;
  getOrder(orderNo: string): Promise<RentOrder>;
  listOrders(q?: OrderQ): Promise<PageResult<RentOrder>>;
  ongoingOrder(): Promise<RentOrder | null>;
  buyout(orderNo: string): Promise<RentOrder>;
  // 支付 / 免押（经 PaymentPort→nearpay，MVP Stub）
  depositFree(cabinetNo: string): Promise<{ authNo: string; frozen: number }>;
  pay(p: PayParams): Promise<PayResult>;
  // 售后
  report(p: ReportInput): Promise<ReportResult>;
  // 钱包 / 营销
  getWallet(): Promise<Wallet>;
  walletTxns(q?: PageQ): Promise<PageResult<WalletTxn>>;
  listCoupons(): Promise<Coupon[]>;
  listMemberships(): Promise<Membership[]>;
}
