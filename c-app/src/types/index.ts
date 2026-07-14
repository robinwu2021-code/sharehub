// C 端契约镜像（后端 powerbank-common-api 就绪后由 openapi 生成替换，前后端零漂移）。
// 对外口径：Result {code,msg,data} + 分页 {records,total,page,size}（对齐 docs/api、ops-web，非 commons）。

export interface Result<T> {
  code: number;
  msg: string;
  data: T;
}
export interface PageResult<T> {
  records: T[];
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
  favorite?: boolean; // 是否已收藏（找柜列表/收藏页共用；后端 /mp/nearby 回填）
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

// 钱包流水（充值/消费/退款/赠金）
export interface WalletTxn {
  txnNo: string;
  type: "RECHARGE" | "SPEND" | "REFUND" | "BONUS";
  title: string;
  amount: number; // 正=入账，负=出账
  currency: string;
  at: string;
}

export interface Coupon {
  couponNo: string;
  title: string;
  amount: number;
  threshold: number;
  status: "UNUSED" | "USED" | "EXPIRED";
  expireAt: string;
}

export interface Membership {
  planNo: string;
  name: string;
  price: number;
  benefits: string[];
  active: boolean;
  expireAt?: string;
}

export interface ReportInput {
  orderNo?: string;
  type: string;
  desc: string;
}
export interface ReportResult {
  reportNo: string;
  woNo: string;
  status: string;
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
export interface Notice {
  noticeNo: string;
  title: string;
  body: string;
  date: string;
  read: boolean;
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
