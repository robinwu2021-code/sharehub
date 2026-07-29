// 覆盖范围：客服工单与会话。
import * as db from "../../mock/db";
import type { CsApi } from "../contracts/cs";
import type { PageQ } from "../query";
import { wait } from "./_wait";

export const csMock: CsApi = {
  listCsTickets: (q: PageQ = {}) => wait(db.listCsTickets(q)),
  listCsSessions: (q: PageQ = {}) => wait(db.listCsSessions(q)),
  saveCsTicket: (x) => wait(db.saveCsTicket(x), 350),
};
