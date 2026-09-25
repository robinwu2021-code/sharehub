// C 端契约镜像（后端 powerbank-common-api 就绪后由 openapi 生成替换，前后端零漂移）。
// 对外口径：**复用 neargo-common-core 的契约** —— Result {code,message,data} + PageResult {total,list}。
//
// 2026-09-23 更正：原本写的是 {code,msg,data} + {records,total}，理由是「对齐 docs/api、ops-web，非 commons」。
// 三点都站不住：
//   · ops-web 读的是 message / list，从来不是 msg / records；
//   · docs/api 自己矛盾（§信封 写 msg、§分页 写 list），错的是 §信封 那一行（已一并修）；
//   · 「非 commons」正是要避免的 —— 两个项目共同依赖 neargo-common-core，
//     各自另造信封就是放弃复用，ai-shop 的 ApiResult{code,msg,data} 是同一个偏离。
//
// 后果是实打实的：msg 让业务错误提示取不到；records 让钱包流水与订单列表**在真后端下永远是空的** ——
// 之前没暴露，是因为 c-app 默认跑 mock，而 mock 也按 records 造数据，两头一起错就看不出来。

export interface Result<T> {
  code: number;
  message: string;
  data: T;
}
export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}

export type Lang = "zh" | "en" | "ar";

// 订单状态机（对齐 C端功能清单 §四 / ops-web OrderStatus）
export type OrderStatus =
  | "CREATED" // 已下单待弹出
  | "DISPENSING" // 弹出中
  | "IN_USE" // 使用中
  | "RETURNED" // 已归还待结算
  | "SETTLED" // 已结算
  | "CLOSED" // 关闭
  | "EXCEPTION"; // 异常/报障

// 附近网点（找柜与地图）
export interface NearbyCabinet {
  cabinetNo: string;
  siteNo: string;
  siteName: string;
  address: string;
  distanceM: number;
  lat: number;
  lng: number;
  availableBorrow: number; // 可借充电宝数
  availableReturn: number; // 可还空仓数
  pricePerHour: number;
  currency: string;
  status: "ACTIVE" | "PAUSED";
}

// 借出可用性校验（借出确认页）
export interface CabinetAvailability {
  cabinetNo: string;
  siteName: string;
  borrowable: boolean;
  pricePerHour: number;
  dailyCap: number; // 日封顶
  buyoutPrice: number; // 买断价
  depositAmount: number; // 押金（降级）
  freeQuota: number; // 免押冻结额度
  currency: string;
}

export interface FeeItem {
  label: string;
  amount: number;
}

export interface RentOrder {
  orderNo: string;
  cUserNo: string;
  status: OrderStatus;
  cabinetNoBorrow: string;
  siteNameBorrow: string;
  cabinetNoReturn?: string;
  siteNameReturn?: string;
  powerBankNo?: string;
  startAt: string;
  endAt?: string;
  durationMin: number;
  amount: number; // 当前/最终费用
  currency: string;
  depositAmount: number;
  freeFrozen: number; // 免押冻结额
  fees: FeeItem[]; // 费用明细拆解
  timeline: { status: OrderStatus; at: string }[];
}

export interface UserProfile {
  cUserNo: string;
  nickname: string;
  avatar?: string;
  phone?: string;
  email?: string;
  creditScore: number;
  freeDeposit: boolean; // 是否已开通免押
  memberLevel?: string;
}

export interface Wallet {
  balance: number;
  bonus: number; // 赠金
  deposit: number; // 押金
  frozen: number; // 免押冻结
  currency: string;
}

/**
 * 钱包流水（镜像后端 `WalletTxnRow`）。
 *
 * ⚠️ 时间字段是 `createdAt` 不是 `at` —— 写成 `at` 的时候每一行的时间都是 undefined，
 * 页面上那一列只剩「充值 · 」半句话，不报错。
 * `bizType`/`bizNo` 是这笔流水的来源单号，对账时靠它反查。
 */
export interface WalletTxn {
  txnNo: string;
  type: "RECHARGE" | "SPEND" | "REFUND" | "BONUS";
  direction: "IN" | "OUT";
  title: string;
  amount: number; // 正=入账，负=出账
  currency: string;
  bizType: string;
  bizNo: string;
  createdAt: string;
}

/** 可购充值套餐（镜像后端 `RechargePackageRow`）。`markets` 是 CSV，如 "AE,SA"。 */
export interface RechargePackage {
  packageNo: string;
  name: string;
  payAmount: number;
  giftAmount: number;
  currency: string;
  markets: string;
  validDays: number | null;
  sortNo: number;
  status: "ENABLED" | "DISABLED";
  archivedAt: string | null;
}

/**
 * 充值结果（镜像后端 `RechargeResultVO`）。
 * 带回充值后的钱包：分两次请求的话，中间那一瞬显示的是旧余额，用户会以为钱没到账。
 * 通道未即时成功时 `status` 仍是 `PENDING`，此时 `balance`/`bonus` 为 null（钱包没动）。
 */
export interface RechargeResult {
  rechargeNo: string;
  packageNo: string;
  payAmount: number;
  giftAmount: number;
  creditAmount: number;
  currency: string;
  status: "PENDING" | "PAID" | "FAILED" | "REFUNDED";
  balance: number | null;
  bonus: number | null;
}

/**
 * 我的券包里的一张券（镜像后端 `UserCouponVO`）。
 *
 * ⚠️ 名字必须和后端 DTO 对得上（`UserCouponVO` 剥掉 `VO` 后缀）——
 * 叫 `Coupon` 的时候对齐脚本配不上对，于是这里少了两个字段也没人知道：
 * 页面读 `title`/`amount`，后端回的是 `tplName`/`value`，卡片上的券名和金额**全是空的**，
 * 而且不报错。
 */
export interface UserCoupon {
  couponNo: string; // 券实例号（CP…），不是模板号
  cUserNo: string;
  tplNo: string;
  tplName: string;
  tplType: "CUT" | "DISCOUNT";
  value: number; // CUT=减免金额；DISCOUNT=折扣率（0..1）
  threshold: number;
  currency: string;
  status: "UNUSED" | "USED" | "EXPIRED";
  usedOrderNo?: string;
  expireAt: string;
}

/** 领券中心的一行（镜像后端 `ClaimableCouponVO`）—— 是**券模板**，领券要传的是 `tplNo`。 */
export interface ClaimableCoupon {
  tplNo: string;
  name: string;
  type: "CUT" | "DISCOUNT";
  value: number;
  threshold: number;
  currency: string;
  remaining: number | null; // null = 不限量，不是「剩 0 张」
  claimed: boolean;
}

export interface Membership {
  planNo: string;
  name: string;
  price: number;
  benefits: string[];
  active: boolean;
  expireAt?: string;
}

/**
 * 报障入参（镜像后端 `ReportReq`）。
 *
 * ⚠️ `problemNo` 是**分流的唯一依据**（来自 `md_problem.suggested_action`）。
 * 不传它，后端一律兜底成「转人工客服」—— 自助解答、自动开工单、自动发起退款
 * 这三条路全都走不到，而且不报错。此前前端传的是本地写死的 `type`/`desc`，
 * 两个字段后端都不认，于是**每一条报障都进了人工队列**。
 */
export interface ReportInput {
  problemNo: string;
  orderNo?: string;
  cabinetNo?: string;
  issue: string; // 用户自己描述的问题
  channel?: string; // APP / MP，缺省由后端填 APP
}

/** 报障受理结果（镜像后端 `ReportResultVO`）。`suggestedAction` 决定提交后往哪跳。 */
export interface ReportResult {
  reportNo: string;
  status: string;
  suggestedAction: "SELF_SERVICE" | "TO_WORKORDER" | "TO_REFUND" | "TO_CS";
  woNo: string | null;
  refundNo: string | null;
  sessionNo: string | null;
  createdAt: string;
}

/** 我的报障单（镜像后端 `CsTicketVO`）。`woNo`/`refundNo` 是处置去向，用来展示进度。 */
export interface CsTicket {
  ticketNo: string;
  userNo: string;
  orderNo: string | null;
  cabinetNo: string | null;
  problemNo: string | null;
  issue: string;
  channel: string;
  status: "OPEN" | "PROCESSING" | "CLOSED";
  handlerNo: string | null;
  woNo: string | null;
  refundNo: string | null;
  createdAt: string;
}

/**
 * 帮助中心 / 报障问题条目（镜像后端 `FaqItem`）。
 * 后端按 `lang` 挑好单语再下发，端上不做三选一。
 */
export interface FaqItem {
  problemNo: string;
  category: string;
  title: string;
  answer: string;
  suggestedAction: "SELF_SERVICE" | "TO_WORKORDER" | "TO_REFUND" | "TO_CS";
  sortNo: number;
}

export interface LoginResult {
  token: string;
  cUserNo: string;
  nickname: string;
  isNew: boolean;
}

// 国家区号（全球版）
export interface Country {
  iso: string; // ISO2, e.g. AE
  dial: string; // +971
  flag: string; // 🇦🇪
  name: string; // United Arab Emirates
}

// 运营公告
/**
 * 注销申请（PDPL 冷静期，后端 usr_logoff）。
 *
 * **注销不是立刻删数据**：先落 PENDING 并给出 coolingUntil，期内可撤销；
 * 到期后由清除作业执行。所以界面必须能显示「几号生效」与「撤销」——
 * 少了这两样，用户点完就再也回不了头，而他并不知道。
 */
export interface LogoffItem {
  cUserNo: string;
  requestedAt: string;
  /** 冷静期截止（yyyy-MM-dd HH:mm:ss）。此刻之前都可撤销。 */
  coolingUntil: string;
  status: "PENDING" | "CANCELLED" | "DONE";
  purgedAt: string | null;
}

/**
 * 运营公告（镜像后端 `NoticeVO`）—— **广播，没有「已读」这回事**。
 *
 * 此前这个类型写成 `{title, body, date, read}`，四个字段后端一个都不返：
 * 后端三语三列全量下发（`title/titleEn/titleAr` + `content/contentEn/contentAr`），
 * 由端按当前语种取；`read` 属于**站内信**（`MessageItem`，每人一份），公告没有。
 * 结果是公告页整页空白，而且那个红点永远亮着 —— 因为 `read` 恒为 undefined。
 */
export interface Notice {
  noticeNo: string;
  title: string;
  titleEn: string;
  titleAr: string;
  content: string;
  contentEn: string;
  contentAr: string;
  type: string;
  pinned: boolean;
  startAt: string | null;
  endAt: string | null;
  status: string;
  publishedBy: string | null;
  createdAt: string;
  archivedAt: string | null;
}

/**
 * 站内信（镜像后端 `MessageItem`）—— 每人一份，有已读态。
 *
 * ⚠️ 类型名必须叫 `MessageItem`，不能简写成 `Message`：对齐脚本把 `Item` 当作
 * **形状后缀**，看到前端有个 `Message` 就判定「那是另一个投影」，于是既不报缺失、
 * 也不逐字段比 —— 字段对不上再也没人拦。
 */
export interface MessageItem {
  messageNo: string;
  type: string;
  title: string;
  body: string;
  read: boolean;
  readAt: string | null;
  createdAt: string;
}

/** 版本检查结果（镜像后端 `AppVersionCheck`）。无在架版本时 `hasUpdate=false`，其余字段可空。 */
export interface AppVersionCheck {
  hasUpdate: boolean;
  versionNo: string | null;
  buildNo: number | null;
  forceUpdate: boolean | null;
  minSupported: string | null;
  releaseNote: string | null;
  downloadUrl: string | null;
}

// 门店详情（附近门店点选）
export interface StoreDetail {
  siteNo: string;
  siteName: string;
  address: string;
  openHours: string;
  lat: number;
  lng: number;
  distanceM: number;
  availableBorrow: number;
  availableReturn: number;
  pricePerHour: number;
  currency: string;
  favorite: boolean;
  cabinets: { cabinetNo: string; borrow: number; return: number }[];
}
