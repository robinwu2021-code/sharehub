import type { AuditTrail } from "./common";
// 覆盖范围：订单域（trade）——租借订单、订单异常、投诉、退款、押金与欠费、
// 预约订单、免费订单及其页头统计。
// 注：免费来源枚举 WhitelistReason 定义在 ./user（白名单归用户域），此处引用。

import type { WhitelistReason } from "./user";

export type OrderStatus =
  | "CREATED" | "DISPENSING" | "IN_USE" | "RETURNED" | "SETTLED" | "CLOSED" | "EXCEPTION";
export interface RentOrder {
  orderNo: string;
  cUserNo: string;
  cabinetNo: string;
  returnCabinetNo: string | null;
  powerbankNo: string | null;
  locationName?: string | null;
  status: OrderStatus;
  rentStartAt: string | null;
  rentEndAt: string | null;
  durationMin: number | null;
  feeAmount: number;
  depositAmount: number;
  currency: string;
}

// —— 订单 · 待建功能补全（trade 域）——
export interface OrderException {
  orderNo: string;
  type: "NOT_EJECTED" | "NOT_RETURNED" | "OVERTIME_BUYOUT" | "DOUBLE_CHARGE";
  cabinetNo: string;
  userNo: string;
  amount: number;
  currency: string;
  status: "OPEN" | "HANDLED";
  createdAt: string;
}

// 押金与欠费管理（订单域 · P2）
export interface DepositRecord {
  depositNo: string;
  orderNo: string;
  userNo: string;
  amount: number;
  currency: string;
  status: "HELD" | "RELEASED" | "BOUGHT_OUT" | "ARREARS"; // 冻结/已解冻/买断/欠费
  arrearsAmount: number; // 欠费金额（0 表示无欠费）
  createdAt: string;
}

// —— 售后处置（订单域 · P1，对标简电云 B1/B2）——
// 投诉问题类型：计费争议 / 未弹出 / 未归还 / 设备故障 / 其他
export type ComplaintIssueType = "BILLING_DISPUTE" | "NOT_EJECTED" | "NOT_RETURNED" | "DEVICE_FAULT" | "OTHER";
// 处理结果：退款 / 补偿 / 驳回 / 已解释
export type ComplaintResolution = "REFUND" | "COMPENSATE" | "REJECT" | "EXPLAINED";

// 投诉订单：比竞品多 workOrderNo —— 投诉可直接转工单，投诉/订单/工单三者串通（对方投诉与工单不通）。
export interface OrderComplaint {
  complaintNo: string;
  orderNo: string; // 关联租借订单（引用 orders mock 真实单号）
  userNo: string;
  issueType: ComplaintIssueType;
  description: string; // 用户描述
  screenshotUrl: string | null; // 投诉截图（列表渲染为「查看」链接）
  submittedAt: string;
  status: "PENDING" | "PROCESSING" | "RESOLVED" | "REJECTED";
  handlerName: string | null; // 处理人
  handledAt: string | null;
  resolution: ComplaintResolution | null; // 处理结果
  resolutionNote: string; // 处理说明
  workOrderNo: string | null; // 转工单后回填
}

// 退款记录：独立审批队列（申请→审批→执行），比竞品多一条审批链。
// idempotencyKey / psgTxnNo 是资金操作可追溯的底线：前者防重复退款，后者对得上 PSP 流水。
export interface RefundRecord extends AuditTrail {
  refundNo: string;
  orderNo: string;
  userNo: string;
  amount: number;
  currency: string;
  reason: string;
  applicantName: string; // 申请人（客服/用户/系统）
  appliedAt: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "EXECUTED" | "FAILED";
  idempotencyKey: string; // 幂等键（同一键只退一次）
  psgTxnNo: string | null; // PSP 支付流水号（未执行时为空）
}

// —— 预约订单（阶段 2）——
// 竞品是电车预约充电桩；充电宝映射为「预约取宝 / 预约还位」（热门点位高峰占位）。
export interface Reservation {
  reservationNo: string;
  userNo: string;
  type: "BORROW" | "RETURN"; // 预约取宝 / 预约还位
  siteNo: string;
  siteName: string;
  cabinetNo: string | null; // 指定机柜（空 = 站点级预约）
  reservedFrom: string;
  reservedTo: string;
  holdFee: number; // 占位费（超时未取产生；规则在业务规则页配置）
  currency: string; // 规格未列，金额展示统一需要币种（AED）
  status: "PENDING" | "FULFILLED" | "EXPIRED" | "CANCELLED";
  orderNo: string | null; // 履约后关联的租借订单
}

// —— 免费订单（阶段 2）——：与免费用户白名单联动，页头做成本管控统计
export interface FreeOrder {
  orderNo: string; // 复用租借订单号
  userNo: string;
  nickname: string;
  whitelistReason: WhitelistReason; // 免费来源
  waivedAmount: number; // 减免金额
  currency: string; // 规格未列，money() 需要（AED）
  siteName: string;
  cabinetNo: string;
  startedAt: string;
  endedAt: string;
  duration: number; // 时长（分）
}

/** 免费订单页头统计（本月免费单数 / 累计减免金额），成本管控口径。 */
export interface FreeOrderStats {
  monthCount: number;
  waivedTotal: number;
  currency: string;
}
