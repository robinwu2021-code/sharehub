// 覆盖范围：客服工单与会话。端点前缀：/api/ops/cs/**
import { client } from "../http-client";
import type { CsApi } from "../contracts/cs";
import type { PageQ } from "../query";

export const csHttp: CsApi = {
  listCsTickets: (q?: PageQ) => client.get("/api/ops/cs/tickets", q),
  listCsSessions: (q?: PageQ) => client.get("/api/ops/cs/sessions", q),
  saveCsTicket: (x) => client.post(x.ticketNo ? `/api/ops/cs/tickets/${x.ticketNo}` : "/api/ops/cs/tickets", x),
};
