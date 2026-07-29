// 覆盖范围：报表域五张只读报表（设备 / 点位 / 财务 / 大屏 / 自定义）。
// 端点前缀：/api/ops/reports/**
import { client } from "../http-client";
import type { ReportApi } from "../contracts/report";
import type { PageQ } from "../query";

export const reportHttp: ReportApi = {
  listReportDevice: (q?: PageQ) => client.get("/api/ops/reports/device", q),
  listReportLocation: (q?: PageQ) => client.get("/api/ops/reports/location", q),
  listReportFinance: (q?: PageQ) => client.get("/api/ops/reports/finance", q),
  listReportScreen: (q?: PageQ) => client.get("/api/ops/reports/screen", q),
  listReportCustom: (q?: PageQ) => client.get("/api/ops/reports/custom", q),
};
