// 覆盖范围：客服工单与会话。
import type { PageQ } from "../query";
import type { PageResult, CsTicket, CsSession } from "../../types";

export interface CsApi {
  listCsTickets(q?: PageQ): Promise<PageResult<CsTicket>>;
  listCsSessions(q?: PageQ): Promise<PageResult<CsSession>>;
  saveCsTicket(x: Partial<CsTicket> & { ticketNo?: string }): Promise<CsTicket>;
}
