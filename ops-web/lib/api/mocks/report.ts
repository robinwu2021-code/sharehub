// 覆盖范围：报表域——三张周期报表 + 趋势 + 实时大屏 + 自定义报表 + 消费者洞察。
import * as db from "../../mock/db";
import type { ReportApi } from "../contracts/report";
import type { PageQ, ReportQ, ReportTrendQ, ReportCustomQ } from "../query";
import { wait } from "./_wait";

export const reportMock: ReportApi = {
  listReportDevice: (q: ReportQ = {}) => wait(db.listReportDevice(q)),
  listReportLocation: (q: ReportQ = {}) => wait(db.listReportLocation(q)),
  listReportFinance: (q: ReportQ = {}) => wait(db.listReportFinance(q)),
  listReportScreen: (q: PageQ = {}) => wait(db.listReportScreen(q)),
  listReportCustom: (q: ReportCustomQ = {}) => wait(db.listReportCustom(q)),
  getReportTrend: (q: ReportTrendQ = {}) => wait(db.getReportTrend(q)),
  getScreenBoard: () => wait(db.getScreenBoard()),
  listReportMetrics: () => wait(db.listReportMetrics()),
  getConsumerInsight: () => wait(db.getConsumerInsight()),
};
