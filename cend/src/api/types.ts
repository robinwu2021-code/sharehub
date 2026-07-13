// C 端领域类型（镜像 docs/api §六 /mp 契约 + ops-web/lib/types 的订单形状）。
// 后端就绪由 powerbank-common-api openapi 生成替换。

// 契约对齐 neargo-common-core（envelope 方案①）。
export interface Result<T> {
  code: number;
  message: string;
  data: T;
}

export interface PageData<T> {
  list: T[];
  total: number;
  page?: number;
  size?: number;
}

export interface PageQ {
  page?: number;
  size?: number;
  keyword?: string;
}

// —— 账户（清单 §1/§16）——
export interface LoginReq {
  channel: "APP" | "MP";
  phone?: string;
  otp?: string;
  appleToken?: string;
  googleToken?: string;
  wechatCode?: string;
}
export interface Profile {
  cUserNo: string;
  nickname: string;
  phone: string;
  creditScore: number;
  memberLevel: string; // NONE / MONTHLY / TIMES
  blacklisted: boolean;
}
export interface LoginResp {
  cUserNo: string;
  token: string;
  expireIn: number;
  profile: Profile;
}

// —— 钱包（清单 §9）——
export interface Wallet {
  balance: number;
  bonus: number;
  deposit: number;
  frozen: number;
  currency: string;
}

// —— 附近网点（清单 §2）——
export interface NearbyCabinet {
  cabinetNo: string;
  siteName: string;
  address: string;
  distanceKm: number;
  available: number; // 可借充电宝数
  returnableSlots: number; // 可还空仓数
  priceBrief: string; // 计费简述
  lat: number;
  lng: number;
}

// —— 订单（清单 §3/§6/§7/§8，状态机对齐 ops-web OrderStatus）——
export type OrderStatus =
  | "CREATED" | "DISPENSING" | "IN_USE" | "RETURNED" | "SETTLED" | "CLOSED" | "EXCEPTION";
export interface RentOrder {
  orderNo: string;
  cUserNo: string;
  cabinetNo: string;
  returnCabinetNo: string | null;
  powerbankNo: string | null;
  siteName: string;
  status: OrderStatus;
  rentStartAt: string | null;
  rentEndAt: string | null;
  durationMin: number | null;
  feeAmount: number;
  depositAmount: number;
  currency: string;
}
export type OrderQ = PageQ & { status?: OrderStatus };

// —— 报障（清单 §12）——
export type ReportType = "NOT_EJECTED" | "CANNOT_RETURN" | "OVERCHARGE" | "OTHER";
export interface ReportReq {
  orderNo?: string;
  type: ReportType;
  desc: string;
}
