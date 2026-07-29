// 覆盖范围：报表域五张只读报表（设备 / 点位 / 财务 / 大屏 / 自定义）。
import * as db from "../../mock/db";
import type { ReportApi } from "../contracts/report";
import type { PageQ } from "../query";
import { wait } from "./_wait";

export const reportMock: ReportApi = {
  listReportDevice: (q: PageQ = {}) => wait(db.listReportDevice(q)),
  listReportLocation: (q: PageQ = {}) => wait(db.listReportLocation(q)),
  listReportFinance: (q: PageQ = {}) => wait(db.listReportFinance(q)),
  listReportScreen: (q: PageQ = {}) => wait(db.listReportScreen(q)),
  listReportCustom: (q: PageQ = {}) => wait(db.listReportCustom(q)),
};
