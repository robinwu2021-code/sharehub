// 覆盖范围：客服域（cs）——客服工单、在线会话。

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
  status: "OPEN" | "PROCESSING" | "CLOSED";
  handlerNo: string | null;
  // 两个出口字段是「处置去向可追溯」的关键，同时充当幂等闸门：
  // 非空即表示已转出，前端据此隐藏转出按钮、直接显示既有单号。
  woNo: string | null;
  refundNo: string | null;
  createdAt: string;
}
export interface CsSession {
  sessionNo: string;
  userNo: string;
  agentName: string;
  lastMessage: string;
  status: "ACTIVE" | "CLOSED";
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
