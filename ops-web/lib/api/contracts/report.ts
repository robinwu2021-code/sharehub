// 覆盖范围：报表域五张只读报表（设备 / 点位 / 财务 / 大屏 / 自定义）。
import type { PageQ } from "../query";
import type {
  PageResult, ReportDevice, ReportLocation, ReportFinance, ReportScreen, ReportCustom,
} from "../../types";

export interface ReportApi {
  listReportDevice(q?: PageQ): Promise<PageResult<ReportDevice>>;
  listReportLocation(q?: PageQ): Promise<PageResult<ReportLocation>>;
  listReportFinance(q?: PageQ): Promise<PageResult<ReportFinance>>;
  listReportScreen(q?: PageQ): Promise<PageResult<ReportScreen>>;
  listReportCustom(q?: PageQ): Promise<PageResult<ReportCustom>>;
}
