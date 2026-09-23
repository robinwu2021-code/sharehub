"use client";

// 运营管理 › 场站管理 › 站点概览（清单 OM-S1，对标简电「站场概览」）
//
// 与经营看板的分工：看板回答「今天整体怎么样」，这一页回答「站点和设备铺得怎么样、哪里出了问题」。
// 在线率与排行的口径和看板一致（同一份纯函数 lib/operation-overview，单测钉住）。
//
// 后端聚合接口尚未实现（lib/backend-ready 登记为未就绪）：真实后端模式下本页在菜单里灰显，
// 直达时给出明确说明；mock 模式可完整预览。
import { Suspense, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import {
  LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
} from "recharts";
import { AlertTriangle, MapPin } from "lucide-react";
import { api } from "@/lib/api";
import type { AttentionItem, OperationOverview, SiteRankMetric } from "@/lib/types";
import { useI18n } from "@/lib/i18n";
import { money } from "@/lib/utils";
import { pageReady } from "@/lib/backend-ready";
import { PageTitle, EmptyState, ErrorState, Skeleton } from "@/components/ui/misc";
import { Tabs } from "@/components/ui/tabs";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { SummaryCard } from "@/components/ui/summary-card";

const DAY = 86400_000;
const RANGES = [
  { key: "7", label: "近 7 日" },
  { key: "30", label: "近 30 日" },
  { key: "90", label: "近 90 日" },
];
const METRICS: { key: SiteRankMetric; label: string }[] = [
  { key: "gmv", label: "GMV" },
  { key: "orders", label: "订单数" },
  { key: "perCabinet", label: "单柜日均订单" },
  { key: "onlineRate", label: "在线率" },
];

const SEVERITY: StatusMap<AttentionItem["severity"]> = {
  high: { label: "紧急", tone: "danger" },
  medium: { label: "关注", tone: "warning" },
  low: { label: "留意", tone: "muted" },
};
const KIND_LABEL: Record<AttentionItem["kind"], string> = {
  ALL_OFFLINE: "设备全部离线",
  NO_CABINET: "营业中但没有机柜",
  CONTRACT_EXPIRED: "合同已过期",
  CONTRACT_SOON: "合同临期",
  NO_PRICE_PLAN: "没有收费方案",
  NO_SHARING: "没有分成配置",
  NO_ORDER: "近 7 日零订单",
};
/** 每类问题的处置入口——「看到问题→当场处置」，不要让人自己去猜去哪修。 */
const KIND_FIX: Record<AttentionItem["kind"], { href: string; label: string }> = {
  ALL_OFFLINE: { href: "/devices", label: "查看设备" },
  NO_CABINET: { href: "/operation/sites", label: "查看站点" },
  CONTRACT_EXPIRED: { href: "/locations?tab=contracts", label: "查看合同" },
  CONTRACT_SOON: { href: "/locations?tab=contracts", label: "查看合同" },
  NO_PRICE_PLAN: { href: "/operation/fee-plans", label: "配置方案" },
  NO_SHARING: { href: "/finance?tab=rules", label: "配置分成" },
  NO_ORDER: { href: "/operation/sites", label: "查看站点" },
};

function OverviewInner() {
  const { tNav } = useI18n();
  const [range, setRange] = useState("7");
  const [metric, setMetric] = useState<SiteRankMetric>("gmv");
  const [kindFilter, setKindFilter] = useState<string>("");

  const days = Number(range);
  const to = new Date();
  const from = new Date(to.getTime() - (days - 1) * DAY);
  const q = useQuery({
    queryKey: ["op", "overview", range],
    queryFn: () => api.getOperationOverview({ from: from.toISOString(), to: to.toISOString() }),
  });
  const d = q.data as OperationOverview | undefined;

  const rankRows = [...(d?.ranking ?? [])]
    .sort((a, b) => (b[metric] as number) - (a[metric] as number))
    .slice(0, 10);
  const rankCols: Column<OperationOverview["ranking"][number]>[] = [
    { header: "#", className: "w-10", cell: (r) => rankRows.indexOf(r) + 1 },
    { header: "站点", cell: (r) => (
      <Link href={`/operation/sites?no=${r.siteNo}`} className="min-w-0 hover:underline">
        <div className="truncate">{r.siteName}</div>
        <div className="truncate txt-caption text-muted-foreground">{r.siteNo} · {r.venueName}</div>
      </Link>
    ) },
    { header: "机柜", className: "text-right", cell: (r) => r.cabinetCount },
    { header: "订单", className: "text-right", cell: (r) => r.orders },
    { header: "GMV", className: "whitespace-nowrap text-right", cell: (r) => money(r.gmv, d?.business.currency) },
    { header: "单柜日均", className: "text-right", cell: (r) => r.perCabinet.toFixed(2) },
    { header: "在线率", className: "text-right", cell: (r) => `${(r.onlineRate * 100).toFixed(0)}%` },
  ];

  const attention = (d?.attention ?? []).filter((a) => !kindFilter || a.kind === kindFilter);
  const kindCounts = new Map<string, number>();
  for (const a of d?.attention ?? []) kindCounts.set(a.kind, (kindCounts.get(a.kind) ?? 0) + 1);

  const attentionCols: Column<AttentionItem>[] = [
    { header: "严重度", className: "whitespace-nowrap", cell: (a) => <StatusBadge map={SEVERITY} value={a.severity} /> },
    // 站点重名在真实数据里也会发生（同一商场的不同楼层），必须带编号，否则无法判断说的是哪一个
    { header: "站点", cell: (a) => (
      <Link href={`/operation/sites?no=${a.siteNo}`} className="min-w-0 hover:underline">
        <div className="truncate">{a.siteName}</div>
        <div className="truncate txt-caption text-muted-foreground">{a.siteNo}</div>
      </Link>
    ) },
    { header: "问题", className: "whitespace-nowrap", cell: (a) => KIND_LABEL[a.kind] },
    { header: "说明", cell: (a) => <span className="txt-caption text-muted-foreground">{a.detail}</span> },
    { header: "处置", className: "whitespace-nowrap", cell: (a) => (
      <Link href={KIND_FIX[a.kind].href} className="txt-caption text-primary hover:underline">{KIND_FIX[a.kind].label}</Link>
    ) },
  ];

  return (
    <div>
      <PageTitle title={tNav("站点概览")} desc="站点与设备的铺设规模、经营排行，以及需要处置的站点" />

      {/* 两种「没东西看」要分开：后端没这个接口是**能力缺失**（空态，没有重试的意义）；
          接口有但这次没取到是**失败**（失败态，给原因和重试）。原先都画成空态。 */}
      {q.isError && (pageReady("overview")
        ? <ErrorState error={q.error} onRetry={q.refetch} />
        : <EmptyState
            title="概览暂不可用"
            desc="站点概览的聚合接口后端尚未实现。本地 mock 模式可以完整预览这一页。"
          />)}
      {q.isLoading && <Skeleton className="h-40" />}

      {d && (
        <>
          <h2 className="mb-2 txt-strong">规模</h2>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
            <SummaryCard label="站点" value={d.scale.siteTotal} sub={`营业中 ${d.scale.siteActive} · 暂停 ${d.scale.sitePaused}`} />
            <SummaryCard label="点位" value={d.scale.pointTotal} />
            <SummaryCard label="机柜" value={d.scale.cabinetTotal} sub={`在线 ${d.scale.cabinetOnline}`} />
            <SummaryCard label="设备在线率" value={`${(d.scale.onlineRate * 100).toFixed(1)}%`} sub="在线且非故障 ÷ 机柜总数" />
            <SummaryCard label="充电宝" value={d.scale.powerbankTotal} sub={`在柜 ${d.scale.powerbankInCabinet} · 借出 ${d.scale.powerbankRented} · 异常 ${d.scale.powerbankFault}`} />
          </div>

          <div className="mt-6 flex items-center justify-between gap-3">
            <h2 className="txt-strong">经营</h2>
            <Tabs tabs={RANGES} value={range} onChange={setRange} />
          </div>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <SummaryCard label="订单数" value={d.business.orders} />
            <SummaryCard label="GMV" value={money(d.business.gmv, d.business.currency)} />
            <SummaryCard label="客单价" value={money(d.business.avgOrderValue, d.business.currency)} />
            <SummaryCard label="单柜日均订单" value={d.business.ordersPerCabinetPerDay.toFixed(2)} />
          </div>
          <div className="mt-4 h-56 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={d.trend} margin={{ top: 8, right: 8, bottom: 0, left: -20 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                <XAxis dataKey="day" tick={{ fontSize: 11 }} tickFormatter={(x: string) => x.slice(5)} />
                <YAxis tick={{ fontSize: 11 }} />
                <Tooltip />
                <Line type="monotone" dataKey="orders" name="订单" stroke="var(--primary)" dot={false} />
                <Line type="monotone" dataKey="gmv" name="GMV" stroke="var(--success)" dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>

          <div className="mt-6 flex items-center justify-between gap-3">
            <h2 className="txt-strong">站点排行 Top 10</h2>
            <Tabs tabs={METRICS} value={metric} onChange={(k) => setMetric(k as SiteRankMetric)} />
          </div>
          <DataTable
            rowKey={(r: OperationOverview["ranking"][number]) => r.siteNo}
            columns={rankCols}
            rows={rankRows}
            empty="所选区间内没有任何站点产生订单。"
          />

          <h2 className="mb-2 mt-6 flex items-center gap-2 txt-strong">
            <AlertTriangle className="size-4 text-warning-ink" aria-hidden />
            待关注站点
            <span className="txt-caption text-muted-foreground">{d.attention.length} 条</span>
          </h2>
          {d.attention.length > 0 && (
            <div className="mb-2 flex flex-wrap gap-2">
              <button className="txt-caption text-muted-foreground hover:underline" onClick={() => setKindFilter("")}>全部</button>
              {[...kindCounts.entries()].map(([kind, n]) => (
                <button
                  key={kind}
                  onClick={() => setKindFilter(kind === kindFilter ? "" : kind)}
                  className={`txt-caption hover:underline ${kind === kindFilter ? "text-primary" : "text-muted-foreground"}`}
                >
                  {KIND_LABEL[kind as AttentionItem["kind"]]} {n}
                </button>
              ))}
            </div>
          )}
          <DataTable
            rowKey={(a: AttentionItem) => `${a.siteNo}-${a.kind}`}
            columns={attentionCols}
            rows={attention}
            empty="没有需要处置的站点：设备在线、合同有效、计费与分成都已配置。"
          />

          <h2 className="mb-2 mt-6 txt-strong">场景分布</h2>
          <DataTable
            rowKey={(s: OperationOverview["scenes"][number]) => s.sceneType}
            columns={[
              { header: "场景", cell: (s: OperationOverview["scenes"][number]) => s.sceneType },
              { header: "站点数", className: "text-right", cell: (s: OperationOverview["scenes"][number]) => s.siteCount },
              { header: "占比", className: "text-right", cell: (s: OperationOverview["scenes"][number]) => `${((s.siteCount / Math.max(1, d.scale.siteTotal)) * 100).toFixed(0)}%` },
              { header: "GMV", className: "whitespace-nowrap text-right", cell: (s: OperationOverview["scenes"][number]) => money(s.gmv, d.business.currency) },
            ]}
            rows={d.scenes}
            empty="还没有站点，因此没有场景分布。"
          />

          <h2 className="mb-2 mt-6 flex items-center gap-2 txt-strong"><MapPin className="size-4" aria-hidden /> 地图</h2>
          <EmptyState
            title={d.geoReady.withGeo === 0 ? "站点还没有经纬度，地图无法显示" : `${d.geoReady.total} 个站点中有 ${d.geoReady.withGeo} 个填了经纬度`}
            desc={d.geoReady.withGeo === 0
              ? "在站点管理里补上经纬度后，这里会显示站点分布地图。"
              : "地图待开发；经纬度补齐后会在这里显示站点分布。"}
          />
        </>
      )}
    </div>
  );
}

export default function OverviewPage() {
  return <Suspense fallback={null}><OverviewInner /></Suspense>;
}
