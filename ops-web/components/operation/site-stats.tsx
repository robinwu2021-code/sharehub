"use client";

// 单站统计（清单 OM-S2「统计」页签，对标简电站场管理的「统计」动作）。
// 后端接口未就绪时（真实后端模式），站点列表不显示「统计」入口，这里也给出明确说明。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";
import { api } from "@/lib/api";
import { money } from "@/lib/utils";
import { pageReady } from "@/lib/backend-ready";
import { Tabs } from "@/components/ui/tabs";
import { DataTable, type Column } from "@/components/ui/data-table";
import { EmptyState, Skeleton } from "@/components/ui/misc";
import { SummaryCard } from "@/components/operation/summary-card";
import type { SiteStats } from "@/lib/types";

const DAY = 86400_000;
const RANGES = [
  { key: "7", label: "近 7 日" },
  { key: "30", label: "近 30 日" },
  { key: "90", label: "近 90 日" },
];

export function SiteStatsPanel({ siteNo }: { siteNo: string }) {
  const [range, setRange] = useState("7");
  const days = Number(range);
  const to = new Date();
  const from = new Date(to.getTime() - (days - 1) * DAY);

  const q = useQuery({
    queryKey: ["op", "site-stats", siteNo, range],
    queryFn: () => api.getSiteStats(siteNo, { from: from.toISOString(), to: to.toISOString() }),
  });
  const s = q.data as SiteStats | undefined;

  const pointCols: Column<SiteStats["byPoint"][number]>[] = [
    { header: "点位", cell: (p) => (
      <div className="min-w-0">
        <div className="truncate">{p.locationName}</div>
        <div className="truncate txt-caption text-muted-foreground">{p.locationNo}</div>
      </div>
    ) },
    { header: "机柜", className: "text-right", cell: (p) => p.cabinetCount },
    { header: "订单", className: "text-right", cell: (p) => p.orders },
    { header: "GMV", className: "whitespace-nowrap text-right", cell: (p) => money(p.gmv, s?.currency) },
    { header: "单柜日均", className: "text-right", cell: (p) => p.perCabinet.toFixed(2) },
  ];

  return (
    <div>
      <Tabs tabs={RANGES} value={range} onChange={setRange} />
      {q.isLoading && <Skeleton className="h-28" />}
      {q.isError && (
        <EmptyState
          title="统计暂不可用"
          desc={pageReady("sites")
            ? "单站统计接口后端尚未实现，切到本地 mock 模式可以预览这一页的形态。"
            : "取数失败，请稍后重试。"}
        />
      )}
      {s && (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
            <SummaryCard label="订单数" value={s.orders} sub={`${RANGES.find((r) => r.key === range)?.label}`} />
            <SummaryCard label="GMV" value={money(s.gmv, s.currency)} sub={`客单价 ${money(s.avgOrderValue, s.currency)}`} />
            <SummaryCard label="平均租借时长" value={`${s.avgDurationMin} 分钟`} sub={s.orders ? undefined : "区间内没有已结束的订单"} />
            <SummaryCard label="单柜日均订单" value={s.ordersPerCabinetPerDay.toFixed(2)} sub={`共 ${s.cabinetCount} 台机柜`} />
            <SummaryCard label="设备在线率" value={`${(s.onlineRate * 100).toFixed(1)}%`} sub="在线且非故障 ÷ 机柜总数" />
          </div>

          <h3 className="mb-2 mt-5 txt-strong">订单与 GMV 趋势</h3>
          <div className="h-52 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={s.trend} margin={{ top: 8, right: 8, bottom: 0, left: -20 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                <XAxis dataKey="day" tick={{ fontSize: 11 }} tickFormatter={(d: string) => d.slice(5)} />
                <YAxis tick={{ fontSize: 11 }} />
                <Tooltip />
                <Line type="monotone" dataKey="orders" name="订单" stroke="var(--primary)" dot={false} />
                <Line type="monotone" dataKey="gmv" name="GMV" stroke="var(--success)" dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>

          <h3 className="mb-2 mt-5 txt-strong">按点位拆分</h3>
          <DataTable
            rowKey={(p: SiteStats["byPoint"][number]) => p.locationNo}
            columns={pointCols}
            rows={s.byPoint}
            empty="这个站点还没有点位，因此没有可拆分的数据。"
          />
        </>
      )}
    </div>
  );
}
