// 覆盖范围：报表域——设备 / 点位 / 财务三张周期报表 + 趋势 + 实时大屏 + 自定义报表
// （指标目录 + 自选指标查询）+ 消费者洞察（漏斗/画像）。
//
// 说明：消费者**分层表** `listConsumerSegments` 历史上落在 user 域（端点同在
// /api/ops/reports/ 下），本批不迁移（会牵动 user 契约与页面），洞察数据落在本域。
import type { PageQ, ReportQ, ReportTrendQ, ReportCustomQ } from "../query";
import type {
  PageResult, ReportDevice, ReportLocation, ReportFinance, ReportScreen, ReportCustom,
  ReportTrend, ReportMetricDef, ScreenBoard, ConsumerInsight,
} from "../../types";

export interface ReportApi {
  listReportDevice(q?: ReportQ): Promise<PageResult<ReportDevice>>;
  listReportLocation(q?: ReportQ): Promise<PageResult<ReportLocation>>;
  listReportFinance(q?: ReportQ): Promise<PageResult<ReportFinance>>;
  listReportScreen(q?: PageQ): Promise<PageResult<ReportScreen>>;
  listReportCustom(q?: ReportCustomQ): Promise<PageResult<ReportCustom>>;
  /** 三张报表共用：kind 决定口径，返回周期桶序列 + 全周期汇总条。 */
  getReportTrend(q?: ReportTrendQ): Promise<ReportTrend>;
  /** 大屏一次取全（KPI + 分时 + 排名 + 柜机构成）：拆成四个请求会让看板分批到达、画面跳。 */
  getScreenBoard(): Promise<ScreenBoard>;
  /** 自定义报表的可选指标目录。 */
  listReportMetrics(): Promise<ReportMetricDef[]>;
  /** 消费者洞察：转化漏斗 + 画像分布。 */
  getConsumerInsight(): Promise<ConsumerInsight>;
}
