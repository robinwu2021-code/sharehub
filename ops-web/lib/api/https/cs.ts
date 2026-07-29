// 覆盖范围：客服工单与会话。端点前缀：/api/cs/**
import { client } from "../http-client";
import type { CsApi } from "../contracts/cs";
import type { PageQ } from "../query";

export const csHttp: CsApi = {
  listCsTickets: (q?: PageQ) => client.get("/api/cs/tickets", q),
  listCsSessions: (q?: PageQ) => client.get("/api/cs/sessions", q),
  saveCsTicket: (x) => client.post(x.ticketNo ? `/api/cs/tickets/${x.ticketNo}` : "/api/cs/tickets", x),
};
