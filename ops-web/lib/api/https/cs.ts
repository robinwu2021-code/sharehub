// 覆盖范围：客服工单与会话。端点前缀：/api/ops/cs/**
import { client } from "../http-client";
import type { CsApi } from "../contracts/cs";
import type { PageQ } from "../query";

export const csHttp: CsApi = {
  listCsTickets: (q?: PageQ) => client.get("/api/ops/cs/tickets", q),
  listCsSessions: (q?: PageQ) => client.get("/api/ops/cs/sessions", q),
  saveCsTicket: (x) => client.post(x.ticketNo ? `/api/ops/cs/tickets/${x.ticketNo}` : "/api/ops/cs/tickets", x),
  refundCsTicket: (no) => client.post(`/api/ops/cs/tickets/${no}/refund`),
  woCsTicket: (no) => client.post(`/api/ops/cs/tickets/${no}/work-order`),
  listCsMessages: (no, limit) => client.get(`/api/ops/cs/sessions/${no}/messages`, { limit }),
  // 只发 content/attach：sender 由后端取登录态，前端多发一个字段就等于给冒名留口子
  replyCsSession: (no, body) => client.post(`/api/ops/cs/sessions/${no}/messages`, body),
};
