// 覆盖范围：客服域（cs）——客服工单、在线会话。

/** 与后端 `CsTicketStatus` 枚举同名同值。**具名不是风格** —— 两端同名词表比对只认
 *  具名 `export type`，内联在 interface 里的联合它一个都发现不了。 */
export type CsTicketStatus = "OPEN" | "PROCESSING" | "CLOSED";
export interface CsTicket {
  ticketNo: string;
  userNo: string;
  /** 关联订单：转退款要拿它去资金域建退款单，没有订单号就退不了钱 */
  orderNo: string | null;
  cabinetNo: string;
  /** 报障分类（md_problem 字典号），决定后端的自动分流方向 */
  problemNo: string | null;
  issue: string;
  channel: string;
  status: CsTicketStatus;
  handlerNo: string | null;
  // 两个出口字段是「处置去向可追溯」的关键，同时充当幂等闸门：
  // 非空即表示已转出，前端据此隐藏转出按钮、直接显示既有单号。
  woNo: string | null;
  refundNo: string | null;
  createdAt: string;
}
/** 与后端 `CsSessionStatus` 枚举同名同值。**具名不是风格** —— 两端同名词表比对只认
 *  具名 `export type`，内联在 interface 里的联合它一个都发现不了。 */
export type CsSessionStatus = "ACTIVE" | "CLOSED";
export interface CsSession {
  sessionNo: string;
  userNo: string;
  agentName: string;
  lastMessage: string;
  status: CsSessionStatus;
  updatedAt: string;
}
/**
 * 会话消息（append-only，只增不改）。
 * `senderType` 区分用户与客服 —— 时间线左右分栏靠它，不靠 senderNo 猜。
 */
export interface CsMessage {
  id: number;
  sessionNo: string;
  senderType: "USER" | "AGENT";
  senderNo: string;
  content: string;
  attach: string | null;
  createdAt: string;
}
