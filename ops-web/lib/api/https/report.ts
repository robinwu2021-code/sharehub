// 覆盖范围：报表域——三张周期报表 + 趋势 + 实时大屏 + 自定义报表 + 消费者洞察。
// 端点前缀：/api/ops/reports/**
//
// ⚠️ 后端缺口（2026-07-30 `scripts/check-backend-parity.py` 核对）：本域**全部端点后端都不存在**
// （报表域后端实现数 0）。本文件是「切后端时的目标形状」，路径按既有约定拼（域前缀 + 资源名，
// 查询参数用 camelCase），但**在后端补齐前一行都跑不通** —— 页面全靠 mock 切片。
// 后端落地时逐条核对下面每个 ⚠️ 标记的路径与入参再删注释。
import { client } from "../http-client";
import type { ReportApi } from "../contracts/report";
import type { PageQ, ReportQ, ReportTrendQ, ReportCustomQ } from "../query";

export const reportHttp: ReportApi = {
  // ⚠️ 后端缺口：period=LAST_7D|LAST_30D|LAST_13W|LAST_12M
  listReportDevice: (q?: ReportQ) => client.get("/api/ops/reports/device", q),
  // ⚠️ 后端缺口
  listReportLocation: (q?: ReportQ) => client.get("/api/ops/reports/location", q),
  // ⚠️ 后端缺口
  listReportFinance: (q?: ReportQ) => client.get("/api/ops/reports/finance", q),
  // ⚠️ 后端缺口
  listReportScreen: (q?: PageQ) => client.get("/api/ops/reports/screen", q),
  // ⚠️ 后端缺口：dim=SITE|SCENE|MONTH & metrics=GMV,ORDERS,...（csv 多值）
  listReportCustom: (q?: ReportCustomQ) => client.get("/api/ops/reports/custom", q),
  // ⚠️ 后端缺口：kind=DEVICE|LOCATION|FINANCE
  getReportTrend: (q?: ReportTrendQ) => client.get("/api/ops/reports/trend", q),
  // ⚠️ 后端缺口：大屏聚合视图（一次返回 KPI/分时/排名/柜机构成）
  getScreenBoard: () => client.get("/api/ops/reports/screen-board"),
  // ⚠️ 后端缺口：自定义报表指标目录
  listReportMetrics: () => client.get("/api/ops/reports/metrics"),
  // ⚠️ 后端缺口：消费者漏斗 + 画像
  getConsumerInsight: () => client.get("/api/ops/reports/consumer-insight"),
};
