// 覆盖范围：报表域五张只读报表（设备 / 点位 / 财务 / 大屏 / 自定义）。
// 端点前缀：/api/report/**
import { client } from "../http-client";
import type { ReportApi } from "../contracts/report";
import type { PageQ } from "../query";

export const reportHttp: ReportApi = {
  listReportDevice: (q?: PageQ) => client.get("/api/report/device", q),
  listReportLocation: (q?: PageQ) => client.get("/api/report/location", q),
  listReportFinance: (q?: PageQ) => client.get("/api/report/finance", q),
  listReportScreen: (q?: PageQ) => client.get("/api/report/screen", q),
  listReportCustom: (q?: PageQ) => client.get("/api/report/custom", q),
};
