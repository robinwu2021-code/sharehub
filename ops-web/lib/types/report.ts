// 覆盖范围：报表域（bi）——设备/点位/财务报表、数据大屏、自定义报表、
// 消费者分群分析。

export interface ReportDevice {
  locationName: string;
  onlineRate: number; // 0..1
  turnover: number;
  faultRate: number; // 0..1
  cabinetCount: number;
}
export interface ReportLocation {
  siteName: string;
  revenue: number;
  cost: number;
  payback: number;
  roi: number;
  currency: string;
}
export interface ReportFinance {
  period: string;
  gmv: number;
  share: number;
  settle: number;
  net: number;
  currency: string;
}
export interface ReportScreen {
  metric: string;
  value: number;
  unit: string;
  trend: number;
}
export interface ReportCustom {
  dim: string;
  metric: string;
  value: number;
}

// 消费者数据分析（报表域 · P3）
export interface ConsumerSegment {
  segmentNo: string;
  segment: string; // 人群/画像名
  userCount: number;
  repeatRate: number; // 复借率 0~1
  avgOrderValue: number;
  currency: string;
}
