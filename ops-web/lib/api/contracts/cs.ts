// 覆盖范围：客服工单与会话。
import type { PageQ } from "../query";
import type { PageResult, CsTicket, CsSession, CsMessage } from "../../types";

export interface CsApi {
  listCsTickets(q?: PageQ): Promise<PageResult<CsTicket>>;
  listCsSessions(q?: PageQ): Promise<PageResult<CsSession>>;
  saveCsTicket(x: Partial<CsTicket> & { ticketNo?: string }): Promise<CsTicket>;
  /** 报障转退款申请。**幂等**：已转过原样返回既有 refundNo，绝不建第二笔（重复退款是资金事故）。 */
  refundCsTicket(ticketNo: string): Promise<CsTicket>;
  /**
   * 报障转维修工单。**幂等**：已转过原样返回既有 woNo，不开第二张单
   * —— 否则同一个故障会派两次现场，是运维成本事故。
   */
  woCsTicket(ticketNo: string): Promise<CsTicket>;
  listCsMessages(sessionNo: string, limit?: number): Promise<CsMessage[]>;
  /** 客服回复。**不传 sender** —— 发送人由后端取登录态，让员工能冒名他人回复是审计上的洞。 */
  replyCsSession(sessionNo: string, body: { content: string; attach?: string }): Promise<CsMessage>;
}
