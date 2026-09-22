// 覆盖范围：客服工单与会话。
import * as db from "../../mock/db";
// 会话消息 / 转退款三个方法尚未经 db/index.ts 转出，直接引子模块（同 mocks/workorder.ts 的做法）
import * as csDb from "../../mock/db/cs";
import type { CsApi } from "../contracts/cs";
import type { PageQ } from "../query";
import { wait } from "./_wait";

export const csMock: CsApi = {
  listCsTickets: (q: PageQ = {}) => wait(db.listCsTickets(q)),
  listCsSessions: (q: PageQ = {}) => wait(db.listCsSessions(q)),
  saveCsTicket: (x) => wait(db.saveCsTicket(x), 350),
  refundCsTicket: (no) => wait(csDb.refundCsTicket(no), 350),
  woCsTicket: (no) => wait(csDb.woCsTicket(no), 350),
  listCsMessages: (no, limit) => wait(csDb.listCsMessages(no, limit)),
  replyCsSession: (no, body) => wait(csDb.replyCsSession(no, body.content, body.attach), 350),
};
