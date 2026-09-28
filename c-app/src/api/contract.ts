// 单一 API 契约（McpApi）。mock 与真实后端各实现一份（mock.ts / http.ts），
// 页面只依赖此接口，切换靠 index.ts 一处开关（消除散落的 if(USE_MOCK)）。对齐 ops-web 模式。
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
  LogoffItem, FileRef } from "@/types";

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
  countryCode?: string; // +971（拨号码，拼手机号用）
  /**
   * ISO alpha-2（AE/SA…），选**登录方式开关**用（后端 sys_login_setting 按国家配）。
   * 与 countryCode 不是一回事：那个是拨号码。后端此前第 5 个字段叫 regionCode
   * 且无人读，c-app 发的 countryCode 在后端 record 里根本不存在、被静默丢弃。
   */
  country?: string;
  phone?: string;
  otp?: string;
  password?: string;
  code?: string; // 小程序 wx.login code / OAuth code
}

/**
 * 后端下发的可用登录方式（`GET /mp/auth/methods`，需求 OPS-SYS-07）。
 *
 * <p>⚠️ 这是**建议**不是保护：后端 `ConsumerAuthService` 另有一道服务端门禁。
 * 端上按它隐入口，是为了别让用户点一个必然失败的页签 ——
 * 此前没有这个接口，密码页签无条件画着，而后端连 password 这个 grantType 都没有。
 */
export interface LoginMethods {
  country: string;
  otp: boolean;
  password: boolean;
  apple: boolean;
  google: boolean;
  forceRealName: boolean;
  otpExpireSec: number;
}
export interface OtpParams {
  countryCode: string;
  phone: string;
  scene?: "login" | "register" | "reset";
}
export interface RegisterParams {
  countryCode: string;
  /** ISO alpha-2，判密码登录开关用（与 countryCode 的拨号码不是一回事）。 */
  country?: string;
  phone: string;
  otp: string;
  nickname: string;
  email?: string;
  /** 留空则不设口令 —— 注册不强制设密码，OTP 登录本身就是完整的登录方式。 */
  password?: string;
}
export interface ResetPwdParams {
  countryCode: string;
  country?: string;
  phone: string;
  otp: string;
  password: string;
}
/** 改密（已登录）。**没有 cUserNo** —— 后端取自会话。 */
export interface ChangePwdParams {
  country?: string;
  oldPassword: string;
  newPassword: string;
}

/**
 * 借出回执。**不是订单本身** —— 后端 `POST /mp/trade/orders/rent` 返的是
 * `RentResult{orderNo, powerbankNo, commandId}`，没有 status、没有金额。
 *
 * 此前这里声明成 `ConsumerOrder`，于是端上读 `order.status` 拿到的是 undefined。
 * 那段代码没报错（undefined 不等于 "IN_USE"，正好落进等待分支），但类型是假的 ——
 * 下一个人照着它写 `order.feeAmount` 就会拿到 undefined 再渲染成 NaN。
 *
 * 出宝成不成功要**另外查订单**（`getOrder` 轮询），这也正是它该有的样子：
 * 下单只是把指令发出去，柜机弹没弹是后面的事。
 */
export interface RentReceipt {
  orderNo: string;
  powerbankNo: string | null;
  commandId: string | null;
}

export interface RentParams {
  cabinetNo: string;
  useFreeDeposit: boolean; // true=免押预授权，false=降级押金
  couponNo?: string;
}
/**
 * 支付用途 —— 值按后端 `pay_order.type` 的词表（大写）。
 *
 * 此前是小写的 `rent | deposit | recharge | membership`，而后端那一列声明的是
 * `DEPOSIT/RENT/BUYOUT`、缺省还写成 `"ORDER"` —— 三种写法混着落进同一列，
 * 按用途筛支付单就一条都筛不出来。
 * `recharge` 不在这里：充值走 `POST /mp/user/recharge`（只收 packageNo，不收金额）。
 */
export type PayScene = "RENT" | "DEPOSIT" | "BUYOUT";
export interface PayParams {
  scene: PayScene;
  orderNo?: string;
  /**
   * **没有 amount**：金额由服务端从订单上取。
   *
   * 此前这里有，而后端「body 里有 amount 就用它」且从不与订单应付比对 ——
   * `{orderNo, amount: 0.01}` 就能给应付 17 的单记一笔 0.01，
   * 而幂等键是 `PAY-<orderNo>`，先到的那笔把这张单的支付金额锁死。
   */
}
export interface PayResult {
  payNo: string;
  status: "SUCCESS" | "PENDING" | "FAILED";
  cashierParams?: Record<string, unknown>; // 收银台唤起参数（真实经 nearpay）
}

export interface McpApi {
  // 认证（换 C 池 Bearer，之后属主鉴权）
  /** 可用登录方式：登录页据此渲染页签、隐掉没开的入口。 */
  loginMethods(country?: string): Promise<LoginMethods>;
  login(p: LoginParams): Promise<ConsumerLogin>;
  sendOtp(p: OtpParams): Promise<{ cooldown: number }>;
  register(p: RegisterParams): Promise<ConsumerLogin>;
  resetPassword(p: ResetPwdParams): Promise<{ ok: true }>;
  /** 改密（已登录）。走 `/mp/user/password` 而不是 `/mp/auth/*` —— 后者整段是 permitAll。 */
  changePassword(p: ChangePwdParams): Promise<{ ok: true }>;
  // 登出：**让后端真正吊销 token**。只清本地 storage 等于没登出 ——
  // 令牌在服务端一直有效到过期（`POST /mp/auth/logout` 早就实现了吊销，前端从没调过）。
  logout(): Promise<void>;
  getProfile(): Promise<UserProfile>;
  updateProfile(p: ProfilePatch): Promise<UserProfile>;
  // 注销（PDPL 冷静期）。三个都要：只有 apply 的话，用户点完看不到生效日期、也撤销不了 ——
  // 那是一扇单向门。后端 UserLogoffService 一直实现着 current/cancel。
  currentLogoff(): Promise<LogoffItem | null>;
  applyLogoff(): Promise<LogoffItem>;
  cancelLogoff(): Promise<LogoffItem>;
  // 公告（广播，无已读）与站内信（每人一份，有已读）。两者不是一回事，别合并。
  listNotices(): Promise<Notice[]>;
  listMessages(q?: PageQ & { type?: string; read?: boolean }): Promise<PageResult<MessageItem>>;
  markMessageRead(messageNo: string): Promise<MessageItem>;
  // 版本检查：platform 由端决定（app/mp-weixin/h5），lang 用于取对应语种的更新说明
  checkVersion(platform: string, lang?: string): Promise<AppVersionCheck>;
  // 找柜与地图
  nearbyCabinets(q?: NearbyQ): Promise<NearbyCabinet[]>;
  cabinetAvailability(cabinetNo: string): Promise<CabinetAvailability>;
  storeDetail(siteNo: string, at?: { lat?: number; lng?: number }): Promise<StoreDetail>;
  // 收藏门店。列表返的是**门店卡片**（与找柜同一形状）—— 收藏页要展示地址、可借可还、价格。
  // 给了坐标才算得出距离；不给则 distanceM 为 null，界面不显示那一段（不是显示 0）。
  listFavorites(at?: { lat?: number; lng?: number }): Promise<NearbyCabinet[]>;
  // 返回切换后是否已收藏 —— 后端就是裸 boolean（见 reference.md），此前这里写成 {favorite}，
  // 调用方读 .favorite 恒为 undefined
  toggleFavorite(siteNo: string): Promise<boolean>;
  // 借还
  rentOrder(p: RentParams): Promise<RentReceipt>;
  getOrder(orderNo: string): Promise<ConsumerOrder>;
  listOrders(q?: OrderQ): Promise<PageResult<ConsumerOrder>>;
  ongoingOrder(): Promise<ConsumerOrder | null>;
  buyout(orderNo: string): Promise<ConsumerOrder>;
  // 支付 / 免押（经 PaymentPort→nearpay，MVP Stub）
  depositFree(cabinetNo: string): Promise<{ authNo: string; frozen: number }>;
  pay(p: PayParams): Promise<PayResult>;
  // 售后。问题字典（/mp/faq）既是帮助中心的内容，也是报障的下拉来源 ——
  // 两处各存一份的话，运营改一处就会不一致。
  /**
   * 问题字典（帮助中心 + 报障下拉的同一个数据源）。
   *
   * ⚠️ **必须传 lang**：后端是「按语言挑好的单语视图，端上不做三选一」
   * （见 FaqItem 的 javadoc），挑的依据就是这个参数。不传就恒回默认语种 ——
   * 界面是阿语、FAQ 是中文。后端那边三语挑选是好的，实测 en/ar 都对。
   */
  listFaq(category?: string, lang?: string): Promise<FaqItem[]>;
  /** 报障截图上传：传本地临时路径（uni.chooseImage 给的），回 fileNo，提交报障时带上。 */
  uploadEvidence(filePath: string): Promise<FileRef>;
  /** 取回一张自己交的截图，返回可直接显示的本地路径（个人数据，服务端不发公开链接）。 */
  fetchEvidence(fileNo: string): Promise<string>;
  report(p: ReportInput): Promise<ReportResult>;
  listReports(q?: PageQ & { status?: string }): Promise<PageResult<CsTicket>>;
  getReport(reportNo: string): Promise<CsTicket | null>;
  // 钱包 / 营销
  getWallet(): Promise<WalletOverview>;
  // 充值：**只传套餐号**。金额由服务端按套餐算 —— 端上传金额等于把定价权交给端。
  listRechargePackages(): Promise<RechargePackage[]>;
  recharge(packageNo: string): Promise<RechargeResult>;
  walletTxns(q?: PageQ): Promise<PageResult<WalletTxn>>;
  // 券包与领券中心是**两份不同的东西**：前者是已领到手的券实例（键 couponNo，CP…），
  // 后者是可领的券模板（键 tplNo）。claim 要传的是 tplNo —— 传券实例号会 400「券模板不存在」。
  listCoupons(): Promise<UserCoupon[]>;
  listClaimableCoupons(): Promise<ClaimableCoupon[]>;
  claimCoupon(tplNo: string): Promise<UserCoupon>;
  listMemberships(): Promise<MembershipPlan[]>;
}
