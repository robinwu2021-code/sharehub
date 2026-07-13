"use client";

import { Suspense, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Pagination, StatCard } from "@/components/ui/misc";
import { Select } from "@/components/ui/input";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { money } from "@/lib/utils";
import type {
  ReportDevice, ReportLocation, ReportFinance, ReportScreen, ReportCustom, ConsumerSegment, PageResult,
} from "@/lib/types";

const SIZE = 10;
const TABS = [
  { key: "device", label: "设备运营分析", phase: 2 as const },
  { key: "location", label: "点位坪效", phase: 2 as const },
  { key: "finance", label: "财务报表", phase: 2 as const },
  { key: "screen", label: "实时大屏", phase: 3 as const },
  { key: "custom", label: "自定义报表", phase: 3 as const },
  { key: "consumer", label: "消费者分析", phase: 3 as const },
];

// 比率 0..1 → 百分比，按容差着色。
function rateTone(rate: number, good: "high" | "low"): "success" | "warning" | "danger" {
  const pct = rate * 100;
  if (good === "high") return pct >= 95 ? "success" : pct >= 85 ? "warning" : "danger";
  return pct <= 2 ? "success" : pct <= 5 ? "warning" : "danger";
}

function ReportsInner() {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "device");
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [metric, setMetric] = useState<string>("all");
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);

  const q = useQuery<PageResult<ReportDevice | ReportLocation | ReportFinance | ReportScreen | ReportCustom | ConsumerSegment>>({
    queryKey: ["report", tab, page, keyword],
    queryFn: () =>
      tab === "device" ? api.listReportDevice({ page, size: SIZE, keyword })
      : tab === "location" ? api.listReportLocation({ page, size: SIZE, keyword })
      : tab === "finance" ? api.listReportFinance({ page, size: SIZE, keyword })
      : tab === "screen" ? api.listReportScreen({ page, size: 100 })
      : tab === "consumer" ? api.listConsumerSegments({ page, size: 100 })
      : api.listReportCustom({ page, size: 100 }),
    placeholderData: keepPreviousData,
  });

  const consumerCols: Column<ConsumerSegment>[] = [
    { header: "人群", cell: (r) => <span className="font-medium">{r.segment}</span> },
    { header: "用户数", cell: (r) => <span className="tabular-nums">{r.userCount.toLocaleString()}</span> },
    { header: "复借率", cell: (r) => <Badge tone={rateTone(r.repeatRate, "high")}>{(r.repeatRate * 100).toFixed(0)}%</Badge> },
    { header: "客单价", cell: (r) => <span className="tabular-nums">{money(r.avgOrderValue, r.currency)}</span> },
  ];

  const deviceCols: Column<ReportDevice>[] = [
    { header: "点位", cell: (r) => <span className="font-medium">{r.locationName}</span> },
    { header: "机柜数", cell: (r) => <span className="tabular-nums">{Math.round(r.cabinetCount)}</span> },
    { header: "在线率", cell: (r) => <Badge tone={rateTone(r.onlineRate, "high")}>{(r.onlineRate * 100).toFixed(1)}%</Badge> },
    { header: "翻台率", cell: (r) => <span className="tabular-nums">{r.turnover.toFixed(1)}</span> },
    { header: "故障率", cell: (r) => <Badge tone={rateTone(r.faultRate, "low")}>{(r.faultRate * 100).toFixed(1)}%</Badge> },
  ];

  const locationCols: Column<ReportLocation>[] = [
    { header: "站点", cell: (r) => <span className="font-medium">{r.siteName}</span> },
    { header: "营收", cell: (r) => <span className="tabular-nums">{money(r.revenue, r.currency)}</span> },
    { header: "成本", cell: (r) => <span className="tabular-nums">{money(r.cost, r.currency)}</span> },
    { header: "回本天数", cell: (r) => <span className="tabular-nums">{Math.round(r.payback)}</span> },
    { header: "ROI", cell: (r) => <Badge tone={r.roi >= 1 ? "success" : r.roi >= 0 ? "warning" : "danger"}>{(r.roi * 100).toFixed(0)}%</Badge> },
  ];

  const financeCols: Column<ReportFinance>[] = [
    { header: "周期", cell: (r) => <span className="font-medium">{r.period}</span> },
    { header: "GMV", cell: (r) => <span className="tabular-nums">{money(r.gmv, r.currency)}</span> },
    { header: "分润", cell: (r) => <span className="tabular-nums">{money(r.share, r.currency)}</span> },
    { header: "结算", cell: (r) => <span className="tabular-nums">{money(r.settle, r.currency)}</span> },
    { header: "净收入", cell: (r) => <span className="tabular-nums font-medium">{money(r.net, r.currency)}</span> },
  ];

  const customRows = (q.data?.list as ReportCustom[] | undefined) ?? [];
  const metricOptions = useMemo(
    () => (tab === "custom" ? Array.from(new Set(customRows.map((r) => r.metric))) : []),
    [tab, customRows],
  );
  const customFiltered = metric === "all" ? customRows : customRows.filter((r) => r.metric === metric);
  const customCols: Column<ReportCustom>[] = [
    { header: "维度", cell: (r) => <span className="font-medium">{r.dim}</span> },
    { header: "指标", cell: (r) => <Badge tone="outline">{r.metric}</Badge> },
    { header: "数值", cell: (r) => <span className="tabular-nums">{Number.isInteger(r.value) ? r.value : r.value.toFixed(2)}</span> },
  ];

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); setMetric("all"); setKeyword(""); }} />

      {(tab === "device" || tab === "location" || tab === "finance") && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder={tab === "device" ? "搜索点位" : tab === "location" ? "搜索站点" : "搜索周期"}
        />
      )}

      {tab === "device" && (
        <DataTable rowKey={(r: ReportDevice) => r.locationName} columns={deviceCols} rows={q.data?.list as ReportDevice[]} loading={q.isLoading} />
      )}
      {tab === "location" && (
        <DataTable rowKey={(r: ReportLocation) => r.siteName} columns={locationCols} rows={q.data?.list as ReportLocation[]} loading={q.isLoading} />
      )}
      {tab === "finance" && (
        <DataTable rowKey={(r: ReportFinance) => r.period} columns={financeCols} rows={q.data?.list as ReportFinance[]} loading={q.isLoading} />
      )}

      {tab === "screen" && (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
          {(q.data?.list as ReportScreen[] | undefined)?.map((s) => (
            <StatCard
              key={s.metric}
              label={s.metric}
              value={<>{Number.isInteger(s.value) ? s.value.toLocaleString() : s.value.toFixed(1)}<span className="ml-1 text-sm text-muted-foreground">{s.unit}</span></>}
              sub={`${s.trend >= 0 ? "▲" : "▼"} ${Math.abs(s.trend * 100).toFixed(1)}% 环比`}
              tone={s.trend >= 0 ? "up" : "down"}
            />
          ))}
        </div>
      )}

      {tab === "consumer" && (
        <DataTable rowKey={(r: ConsumerSegment) => r.segmentNo} columns={consumerCols} rows={q.data?.list as ConsumerSegment[]} loading={q.isLoading} />
      )}

      {tab === "custom" && (
        <>
          <div className="mb-4 flex items-center gap-2">
            <span className="text-sm text-muted-foreground">指标</span>
            <Select className="w-48" value={metric} onChange={(e) => setMetric(e.target.value)}>
              <option value="all">全部指标</option>
              {metricOptions.map((m) => <option key={m} value={m}>{m}</option>)}
            </Select>
          </div>
          <DataTable rowKey={(r: ReportCustom) => `${r.dim}-${r.metric}`} columns={customCols} rows={customFiltered} loading={q.isLoading} />
        </>
      )}

      {(tab === "device" || tab === "location" || tab === "finance") && q.data && (
        <Pagination page={page} size={SIZE} total={q.data.total} onPage={setPage} />
      )}
    </div>
  );
}

export default function ReportsPage() {
  return <Suspense fallback={null}><ReportsInner /></Suspense>;
}
