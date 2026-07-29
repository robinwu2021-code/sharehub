// 覆盖范围：客服域（cs）——客服工单、在线会话。

export interface CsTicket {
  ticketNo: string;
  userNo: string;
  cabinetNo: string;
  issue: string;
  channel: string;
  status: "OPEN" | "PROCESSING" | "CLOSED";
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
