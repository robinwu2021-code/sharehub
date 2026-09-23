"use client";

import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import {
  Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, ComposedChart, Legend, Line,
  Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from "recharts";
import { Maximize2, Minimize2, RefreshCw } from "lucide-react";
import { UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import { Pagination, Skeleton, StatCard } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab } from "@/lib/hooks/use-page-tab";
import { Card, CardHeader, CardTitle, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { FilterSelect } from "@/components/ui/filter-select";
import { MultiSelect } from "@/components/ui/multi-select";
import { Notice } from "@/components/ui/notice";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { cn, money } from "@/lib/utils";
import { exportCsv } from "@/lib/export-csv";
import {
  REPORT_PERIODS, REPORT_PERIOD_DEFAULT, REPORT_CUSTOM_DIMS, REPORT_METRICS, REPORT_METRICS_DEFAULT,
} from "@/lib/types";
import type {
  ReportDevice, ReportLocation, ReportFinance, ReportCustom, ReportPeriod, ReportTrend,
  ReportTrendKind, ReportMetricDef, ScreenBoard, ConsumerSegment, ConsumerInsight, PageResult,
} from "@/lib/types";

// tab 只声明有哪些、什么顺序；名字与权限来自 nav.ts（见 navTabs）
const TAB_KEYS = ["device", "location", "finance", "screen", "custom", "consumer"] as const;

/** 三张周期报表 → 趋势口径。tab 与 kind 一一对应，别在多处各写一次映射。 */
const TREND_KIND: Record<string, ReportTrendKind> = {
  device: "DEVICE", location: "LOCATION", finance: "FINANCE",
};

/**
 * 指标达标分级。**分级本身要有文字**——规范 §11.4：只靠红/黄/绿着色，
 * 红绿色盲（男性约 8%）读不出「这一格是好还是坏」。
 * 分级语义只描述"离目标多远"，与指标方向无关，故越高越好/越低越好共用一套档。
 */
type RateGrade = "GOOD" | "WATCH" | "ALERT";
const RATE_GRADE: StatusMap<RateGrade> = {
  GOOD: { label: "达标", tone: "success" },
  WATCH: { label: "关注", tone: "warning" },
  ALERT: { label: "告警", tone: "danger" },
};
// 比率 0..1 → 分级；容差沿用原着色阈值，只换表达形式不换判据。
function rateGrade(rate: number, good: "high" | "low"): RateGrade {
  const pct = rate * 100;
  if (good === "high") return pct >= 95 ? "GOOD" : pct >= 85 ? "WATCH" : "ALERT";
  return pct <= 2 ? "GOOD" : pct <= 5 ? "WATCH" : "ALERT";
}
// ROI 不是 0..1 的比率（可 >1、可为负），单独一档判据：回本(≥100%) / 未回本 / 亏损。
const roiGrade = (roi: number): RateGrade => (roi >= 1 ? "GOOD" : roi >= 0 ? "WATCH" : "ALERT");

/** 数值在前、分级在后：数字右对齐等宽便于纵向扫描（§12.4），严重度由文字+色调共同承载。 */
function GradedRate({ text, grade }: { text: string; grade: RateGrade }) {
  return (
    <span className="inline-flex items-center justify-end gap-1.5">
      <span className="tabular-nums">{text}</span>
      <StatusBadge map={RATE_GRADE} value={grade} />
    </span>
  );
}

/** 汇总条/自定义报表共用的取值渲染：格式由数据自带的 `format` 决定，不在页面按 label 猜。 */
function fmtMetric(v: number, format: "MONEY" | "RATE" | "NUMBER", currency = "AED") {
  if (format === "MONEY") return money(v, currency);
  if (format === "RATE") return `${(v * 100).toFixed(1)}%`;
  return Number.isInteger(v) ? v.toLocaleString() : v.toFixed(2);
}

const periodLabel = (p: string) => REPORT_PERIODS.find((x) => x.value === p)?.label ?? p;

// recharts 公共样式（与工作台折线保持一致，走主题变量，换肤不掉色）。
//
// 本页所有系列一律 `isAnimationActive={false}`：recharts 2.x 的入场动画在 React 19 下
// 会停在 t=0（路径所有点挤在首个 x 上，画面像只有一根竖线；工作台那条折线目前干脆不出线，
// 同一个病）。何况大屏每 30s 轮询一次，动画每次重放本身也不适合值班盯屏。
const AXIS = { stroke: "var(--muted-foreground)", fontSize: 12 } as const;
const TIP_STYLE = {
  background: "var(--card)", border: "1px solid var(--border)", borderRadius: 8, fontSize: 12,
} as const;

function PeriodSelect({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  // 不给「全部周期」空值项：报表没有「不限时间」这种口径，周期是必选的。
  return (
    <FilterSelect
      className="w-32"
      aria-label="统计周期"
      value={value}
      onChange={onChange}
      options={REPORT_PERIODS.map((p) => ({ value: p.value, label: p.label }))}
    />
  );
}

function SummaryBar({ trend }: { trend?: ReportTrend }) {
  return (
    <div className="mb-4 grid grid-cols-2 gap-4 lg:grid-cols-4">
      {trend
        ? trend.summary.map((s) => (
          <StatCard key={s.label} label={s.label} value={fmtMetric(s.value, s.format, trend.currency)} />
        ))
        : Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-24" />)}
    </div>
  );
}

/** 周期趋势图。三种口径三种图形，但都吃同一份桶序列 —— 图与表同源，不会各说各话。 */
function TrendChart({ trend, loading }: { trend?: ReportTrend; loading?: boolean }) {
  const data = useMemo(
    () => (trend?.points ?? []).map((p) => ({
      ...p,
      onlinePct: Number((p.onlineRate * 100).toFixed(1)),
      faultPct: Number((p.faultRate * 100).toFixed(2)),
    })),
    [trend],
  );
  const title = trend?.kind === "DEVICE" ? "在线率 / 订单趋势"
    : trend?.kind === "LOCATION" ? "营收 / 成本趋势"
    : "GMV / 分润 / 净收入趋势";

  return (
    <Card className="mb-4">
      <CardHeader><CardTitle>{title}</CardTitle></CardHeader>
      <CardContent>
        <div className="h-64 w-full">
          {loading || !trend ? <Skeleton className="h-full w-full" /> : (
            <ResponsiveContainer width="100%" height="100%">
              {trend.kind === "DEVICE" ? (
                <ComposedChart data={data} margin={{ left: -12, right: 8, top: 8 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                  <XAxis dataKey="bucket" {...AXIS} />
                  <YAxis yAxisId="l" {...AXIS} />
                  {/* 右轴固定 0~100：在线率(≈95) 与故障率(≈2) 共用一根百分比轴，
                      收窄到 [80,100] 会把故障率顶出定义域，recharts 反过来把轴撑成 1.22/26.22 这种脏刻度。 */}
                  <YAxis yAxisId="r" orientation="right" domain={[0, 100]} {...AXIS} />
                  <Tooltip contentStyle={TIP_STYLE} />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Bar yAxisId="l" dataKey="orders" name="订单" fill="var(--primary)" radius={[4, 4, 0, 0]} isAnimationActive={false} />
                  <Line yAxisId="r" type="monotone" dataKey="onlinePct" name="在线率%" stroke="var(--success)" strokeWidth={2} dot={false} isAnimationActive={false} />
                  <Line yAxisId="r" type="monotone" dataKey="faultPct" name="故障率%" stroke="var(--destructive)" strokeWidth={2} dot={false} isAnimationActive={false} />
                </ComposedChart>
              ) : trend.kind === "LOCATION" ? (
                <AreaChart data={data} margin={{ left: -12, right: 8, top: 8 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                  <XAxis dataKey="bucket" {...AXIS} />
                  <YAxis {...AXIS} />
                  <Tooltip contentStyle={TIP_STYLE} />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Area type="monotone" dataKey="revenue" name="营收" stroke="var(--primary)" fill="var(--primary)" fillOpacity={0.15} strokeWidth={2} isAnimationActive={false} />
                  <Area type="monotone" dataKey="cost" name="成本" stroke="var(--destructive)" fill="var(--destructive)" fillOpacity={0.12} strokeWidth={2} isAnimationActive={false} />
                </AreaChart>
              ) : (
                <ComposedChart data={data} margin={{ left: -12, right: 8, top: 8 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                  <XAxis dataKey="bucket" {...AXIS} />
                  {/* 三个系列同为金额，共用一根轴——同一单位放两根轴是在骗眼睛。
                      ⚠️ 柱子取的是 `revenue`：趋势点的字段名是 revenue，财务口径下它就是 GMV
                      （表格列名叫 gmv）。写成 dataKey="gmv" 时 recharts 拿不到值，会静默不画柱、
                      连左轴刻度都不出——不报错，只是「图少一个系列」，极难发现。 */}
                  <YAxis {...AXIS} />
                  <Tooltip contentStyle={TIP_STYLE} />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  {/* 只用一组柱：近 30 日是 30 个桶，两组柱各自宽不到 4px，不如柱+线分工清楚。 */}
                  <Bar dataKey="revenue" name="GMV" fill="var(--primary)" radius={[4, 4, 0, 0]} isAnimationActive={false} />
                  <Line type="monotone" dataKey="share" name="分润" stroke="var(--muted-foreground)" strokeWidth={2} dot={false} isAnimationActive={false} />
                  <Line type="monotone" dataKey="net" name="净收入" stroke="var(--success)" strokeWidth={2} dot={false} isAnimationActive={false} />
                </ComposedChart>
              )}
            </ResponsiveContainer>
          )}
        </div>
      </CardContent>
    </Card>
  );
}

// ————————————————————————————————————————————————————————————————
// 实时大屏（拍板点 #2：真全屏看板，不是表格）
// ————————————————————————————————————————————————————————————————

// 大屏固定深色：值班室投屏惯例，且深底上曲线对比度最高——这里刻意不跟随应用皮肤。
const BOARD_BG = "#0b1120";
const BOARD_LINE = "#1e293b";
const BOARD_COLORS = ["#38bdf8", "#34d399", "#fbbf24", "#f87171", "#a78bfa"];
// 大屏自带一套深色控件（应用层 Button 在深底上不成立），但形状与可访问性照规范：
// 控件用 `rounded-field` 六档圆角（§5 药丸只给徽章），且必须有可见焦点环（§11.2）。
const BOARD_BTN = "inline-flex items-center gap-1 rounded-field bg-white/10 px-3 py-1 text-xs text-white/80 "
  + "hover:bg-white/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/70";
// 大屏内的分块：卡片档圆角（§5 `rounded-card`），深底靠描边而非阴影分层（§6 暗色规则）。
const BOARD_PANEL = "rounded-card bg-white/[0.04] p-4 ring-1 ring-white/10";

function BoardTile({ metric, value, unit, trend, big }: {
  metric: string; value: number; unit: string; trend: number; big?: boolean;
}) {
  return (
    <div className={BOARD_PANEL}>
      <div className={cn("text-white/60", big ? "text-sm" : "text-xs")}>{metric}</div>
      <div className={cn("mt-1 font-medium tabular-nums text-white", big ? "text-4xl" : "text-2xl")}>
        {Number.isInteger(value) ? value.toLocaleString() : value.toFixed(1)}
        <span className="ms-1 text-xs text-white/50">{unit}</span>
      </div>
      {/* 存量口径（在线柜机/借出中/待处理工单）在 mock 里没有历史快照，环比给 0 → 这里渲染「—」，
          不编一个 0.0% 出来充数。 */}
      <div className={cn("mt-1 text-xs", trend === 0 ? "text-white/35" : trend > 0 ? "text-emerald-400" : "text-rose-400")}>
        {trend === 0 ? "— 环比" : `${trend > 0 ? "▲" : "▼"} ${Math.abs(trend * 100).toFixed(1)}% 环比`}
      </div>
    </div>
  );
}

function ScreenBoardView() {
  const q = useQuery<ScreenBoard>({
    queryKey: ["screen-board"],
    queryFn: () => api.getScreenBoard(),
    // 大屏是长时间挂着看的，自己会转；间隔取 30s，与告警轮询同量级。
    refetchInterval: 30_000,
  });
  const [immersive, setImmersive] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);

  // 真全屏优先走 Fullscreen API；被浏览器/iframe 拒绝时退化为 fixed 全窗覆盖。
  // 静态导出没有服务端可依赖，覆盖层是唯一能保证「一定铺满」的兜底。
  const toggle = () => {
    if (immersive) {
      setImmersive(false);
      if (typeof document !== "undefined" && document.fullscreenElement) void document.exitFullscreen().catch(() => {});
      return;
    }
    setImmersive(true);
    void boxRef.current?.requestFullscreen?.().catch(() => {});
  };

  // 用户按浏览器原生退出（F11 / ESC）时同步收起覆盖层，否则会「退出了还盖着」。
  useEffect(() => {
    const onFs = () => { if (!document.fullscreenElement) setImmersive(false); };
    document.addEventListener("fullscreenchange", onFs);
    return () => document.removeEventListener("fullscreenchange", onFs);
  }, []);
  // 降级路径下没有原生全屏，ESC 得自己接。
  useEffect(() => {
    if (!immersive) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setImmersive(false); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [immersive]);

  const d = q.data;

  return (
    <div
      ref={boxRef}
      style={{ background: BOARD_BG }}
      className={cn(
        "rounded-card p-5",
        immersive && "fixed inset-0 z-50 overflow-y-auto rounded-none p-6",
      )}
    >
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className={cn("font-medium text-white", immersive ? "text-xl" : "text-base")}>运营实时看板</div>
        <span className="text-xs text-white/50">
          数据时间 {d ? new Date(d.updatedAt).toLocaleString() : "—"}
          {q.isFetching && " · 刷新中"}
        </span>
        <div className="ms-auto flex items-center gap-2">
          <button type="button" onClick={() => void q.refetch()} className={BOARD_BTN}>
            <RefreshCw className="size-3.5" /> 刷新
          </button>
          <button type="button" onClick={toggle} className={BOARD_BTN}>
            {immersive ? <Minimize2 className="size-3.5" /> : <Maximize2 className="size-3.5" />}
            {immersive ? "退出全屏（ESC）" : "全屏"}
          </button>
        </div>
      </div>

      {!d ? (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => <div key={i} className="h-24 animate-pulse rounded-card bg-white/[0.06]" />)}
        </div>
      ) : (
        <>
          <div className={cn("grid gap-3", immersive ? "grid-cols-2 sm:grid-cols-4" : "grid-cols-2 sm:grid-cols-4")}>
            {d.kpis.map((k) => (
              <BoardTile key={k.metric} metric={k.metric} value={k.value} unit={k.unit} trend={k.trend} big={immersive} />
            ))}
          </div>

          <div className="mt-4 grid gap-4 lg:grid-cols-3">
            <div className={cn(BOARD_PANEL, "lg:col-span-2")}>
              <div className="mb-2 text-sm text-white/60">今日分时 GMV / 订单</div>
              <div className={cn("w-full", immersive ? "h-72" : "h-56")}>
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={d.today} margin={{ left: -16, right: 8, top: 8 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke={BOARD_LINE} />
                    <XAxis dataKey="hour" stroke="#64748b" fontSize={11} />
                    <YAxis stroke="#64748b" fontSize={11} />
                    <Tooltip contentStyle={{ background: BOARD_BG, border: `1px solid ${BOARD_LINE}`, borderRadius: 8, fontSize: 12, color: "#e2e8f0" }} />
                    <Area type="monotone" dataKey="gmv" name={`GMV(${d.currency})`} stroke={BOARD_COLORS[0]} fill={BOARD_COLORS[0]} fillOpacity={0.2} strokeWidth={2} isAnimationActive={false} />
                    <Area type="monotone" dataKey="orders" name="订单" stroke={BOARD_COLORS[1]} fill={BOARD_COLORS[1]} fillOpacity={0.15} strokeWidth={2} isAnimationActive={false} />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </div>

            <div className={BOARD_PANEL}>
              <div className="mb-2 text-sm text-white/60">柜机在线构成</div>
              <div className={cn("w-full", immersive ? "h-72" : "h-56")}>
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie data={d.cabinetStatus} dataKey="value" nameKey="label" innerRadius="55%" outerRadius="80%" paddingAngle={2} isAnimationActive={false}>
                      {d.cabinetStatus.map((s, i) => <Cell key={s.label} fill={BOARD_COLORS[i % BOARD_COLORS.length]} />)}
                    </Pie>
                    <Legend wrapperStyle={{ fontSize: 12, color: "#94a3b8" }} />
                    <Tooltip contentStyle={{ background: BOARD_BG, border: `1px solid ${BOARD_LINE}`, borderRadius: 8, fontSize: 12, color: "#e2e8f0" }} />
                  </PieChart>
                </ResponsiveContainer>
              </div>
            </div>
          </div>

          <div className={cn(BOARD_PANEL, "mt-4")}>
            <div className="mb-2 text-sm text-white/60">站点今日 GMV 排名</div>
            <table className="w-full text-sm text-white/80">
              <thead>
                <tr className="border-b border-white/10 text-left text-xs text-white/45">
                  <th className="w-10 pb-2">#</th>
                  <th className="pb-2">站点</th>
                  <th className="pb-2 text-right">GMV</th>
                  <th className="pb-2 text-right">订单</th>
                </tr>
              </thead>
              <tbody>
                {d.ranking.map((r) => (
                  <tr key={r.siteNo} className="border-b border-white/5 last:border-0">
                    <td className="py-1.5 tabular-nums text-white/45">{r.rank}</td>
                    <td className="py-1.5">{r.siteName}</td>
                    <td className="py-1.5 text-right tabular-nums">{money(r.gmv, d.currency)}</td>
                    <td className="py-1.5 text-right tabular-nums">{r.orders}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}

// ————————————————————————————————————————————————————————————————
// 消费者分析：漏斗 + 画像
// ————————————————————————————————————————————————————————————————

function ConsumerInsightView({ insight }: { insight?: ConsumerInsight }) {
  const dims = useMemo(() => {
    const out: { dim: string; dimLabel: string; slices: ConsumerInsight["profiles"] }[] = [];
    for (const p of insight?.profiles ?? []) {
      const hit = out.find((x) => x.dim === p.dim);
      if (hit) hit.slices.push(p);
      else out.push({ dim: p.dim, dimLabel: p.dimLabel, slices: [p] });
    }
    return out;
  }, [insight]);

  if (!insight) {
    return (
      <div className="mb-4 grid gap-4 lg:grid-cols-2">
        <Skeleton className="h-72" /><Skeleton className="h-72" />
      </div>
    );
  }

  return (
    <>
      <Card className="mb-4">
        <CardHeader>
          <CardTitle>借还转化漏斗</CardTitle>
        </CardHeader>
        <CardContent>
          {/* 漏斗锚在「成功借出 = 人群分层表用户数合计」，上游环节按转化率反推：
              同一 tab 的图与表必须是同一批人，否则两个数字对不上就没人信了。 */}
          <div className="h-64 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={insight.funnel} layout="vertical" margin={{ left: 16, right: 24 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" horizontal={false} />
                <XAxis type="number" {...AXIS} />
                <YAxis type="category" dataKey="stage" width={84} {...AXIS} />
                <Tooltip contentStyle={TIP_STYLE} />
                <Bar dataKey="users" name="人数" fill="var(--primary)" radius={[0, 4, 4, 0]} isAnimationActive={false} />
              </BarChart>
            </ResponsiveContainer>
          </div>
          <div className="mt-3 grid grid-cols-2 gap-2 text-xs sm:grid-cols-5">
            {insight.funnel.map((s) => (
              <div key={s.stage} className="rounded-card bg-muted px-2.5 py-2">
                <div className="text-muted-foreground">{s.stage}</div>
                <div className="mt-0.5 tabular-nums">{s.users.toLocaleString()} 人</div>
                <div className="text-muted-foreground">
                  整体 {(s.rate * 100).toFixed(1)}%
                  {s.dropRate > 0 && ` · 流失 ${(s.dropRate * 100).toFixed(1)}%`}
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      <div className="mb-4 grid gap-4 lg:grid-cols-3">
        {dims.map((d) => (
          <Card key={d.dim}>
            <CardHeader><CardTitle>{`画像 · ${d.dimLabel}`}</CardTitle></CardHeader>
            <CardContent>
              <div className="h-52 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie data={d.slices} dataKey="value" nameKey="label" innerRadius="50%" outerRadius="78%" paddingAngle={2} isAnimationActive={false}>
                      {d.slices.map((s, i) => <Cell key={s.label} fill={BOARD_COLORS[i % BOARD_COLORS.length]} />)}
                    </Pie>
                    <Legend wrapperStyle={{ fontSize: 12 }} />
                    <Tooltip contentStyle={TIP_STYLE} />
                  </PieChart>
                </ResponsiveContainer>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </>
  );
}

// ————————————————————————————————————————————————————————————————
// 自定义报表：维度 × 自选指标
// ————————————————————————————————————————————————————————————————

interface WideRow { dim: string; values: Record<string, number> }

function ReportsInner() {
  const paging = usePaging();
  const onTabChange = () => { paging.reset(); setKeyword(""); };
  const tabs = useNavTabs("/reports", TAB_KEYS);
  const { tab, setTab } = usePageTab(tabs, onTabChange);
  const [keyword, setKeyword] = useState("");
  const [period, setPeriod] = useState<ReportPeriod>(REPORT_PERIOD_DEFAULT);
  const [dim, setDim] = useState<string>("SITE");
  const [metrics, setMetrics] = useState<string[]>([...REPORT_METRICS_DEFAULT]);

  const isPeriodTab = tab === "device" || tab === "location" || tab === "finance";

  const q = useQuery<PageResult<ReportDevice | ReportLocation | ReportFinance | ConsumerSegment>>({
    queryKey: ["report", tab, paging.page, paging.size, keyword, period],
    queryFn: () =>
      tab === "device" ? api.listReportDevice({ page: paging.page, size: paging.size, keyword, period })
      : tab === "location" ? api.listReportLocation({ page: paging.page, size: paging.size, keyword, period })
      : tab === "finance" ? api.listReportFinance({ page: paging.page, size: paging.size, keyword, period })
      : api.listConsumerSegments({ page: paging.page, size: paging.size }),
    enabled: isPeriodTab || tab === "consumer",
    placeholderData: keepPreviousData,
  });

  // 趋势与汇总条：与表格同一个周期入参，同一份桶 —— 表变图必变。
  const trendQ = useQuery<ReportTrend>({
    queryKey: ["report-trend", tab, period],
    queryFn: () => api.getReportTrend({ kind: TREND_KIND[tab], period }),
    enabled: isPeriodTab,
    placeholderData: keepPreviousData,
  });

  const insightQ = useQuery<ConsumerInsight>({
    queryKey: ["consumer-insight"],
    queryFn: () => api.getConsumerInsight(),
    enabled: tab === "consumer",
  });

  // 指标目录来自服务端（页面只兜一份常量做首屏兜底），避免「后端加了指标前端看不到」。
  const metricCatalogQ = useQuery<ReportMetricDef[]>({
    queryKey: ["report-metrics"],
    queryFn: () => api.listReportMetrics(),
    enabled: tab === "custom",
  });
  const catalog: readonly ReportMetricDef[] = metricCatalogQ.data ?? REPORT_METRICS;
  // 按目录顺序排列已勾选指标：勾选顺序不该决定列序，否则同一份报表两次导出列不一样。
  const pickedMetrics = catalog.filter((m) => metrics.includes(m.key));

  const customQ = useQuery<PageResult<ReportCustom>>({
    queryKey: ["report-custom", dim, period, metrics.join(",")],
    queryFn: () => api.listReportCustom({ page: 1, size: UNPAGED_SIZE, dim, period, metrics: metrics.join(",") }),
    enabled: tab === "custom" && metrics.length > 0,
    placeholderData: keepPreviousData,
  });

  // 长表（dim×metric×value）→ 宽表：一行一个维度值，勾了几个指标就几列。
  const wideRows: WideRow[] = useMemo(() => {
    const out: WideRow[] = [];
    for (const r of customQ.data?.list ?? []) {
      const hit = out.find((x) => x.dim === r.dim);
      if (hit) hit.values[r.metric] = r.value;
      else out.push({ dim: r.dim, values: { [r.metric]: r.value } });
    }
    return out;
  }, [customQ.data]);
  const chartMetric = pickedMetrics[0];

  // 数字列一律 `className: "text-end"`（§12.4 数字右对齐 + 等宽）——className 同时落在
  // 表头与单元格上，标题跟着数字一起靠右，否则列头与数值各站一边、反而更难对齐。
  const NUM = "text-end";

  const consumerCols: Column<ConsumerSegment>[] = [
    { header: "人群", cell: (r) => <span className="font-medium">{r.segment}</span> },
    { header: "用户数", className: NUM, cell: (r) => <span className="tabular-nums">{r.userCount.toLocaleString()}</span> },
    { header: "复借率", className: NUM, cell: (r) => <GradedRate text={`${(r.repeatRate * 100).toFixed(0)}%`} grade={rateGrade(r.repeatRate, "high")} /> },
    { header: "客单价", className: NUM, cell: (r) => <span className="tabular-nums">{money(r.avgOrderValue, r.currency)}</span> },
  ];

  const deviceCols: Column<ReportDevice>[] = [
    { header: "站点", cell: (r) => <span className="font-medium">{r.locationName}</span> },
    { header: "机柜数", className: NUM, cell: (r) => <span className="tabular-nums">{Math.round(r.cabinetCount)}</span> },
    { header: "订单数", className: NUM, cell: (r) => <span className="tabular-nums">{r.orders.toLocaleString()}</span> },
    { header: "在线率", className: NUM, cell: (r) => <GradedRate text={`${(r.onlineRate * 100).toFixed(1)}%`} grade={rateGrade(r.onlineRate, "high")} /> },
    { header: "翻台率", className: NUM, cell: (r) => <span className="tabular-nums">{r.turnover.toFixed(1)}</span> },
    { header: "故障率", className: NUM, cell: (r) => <GradedRate text={`${(r.faultRate * 100).toFixed(1)}%`} grade={rateGrade(r.faultRate, "low")} /> },
  ];

  const locationCols: Column<ReportLocation>[] = [
    { header: "站点", cell: (r) => <span className="font-medium">{r.siteName}</span> },
    { header: "营收", className: NUM, cell: (r) => <span className="tabular-nums">{money(r.revenue, r.currency)}</span> },
    { header: "成本", className: NUM, cell: (r) => <span className="tabular-nums">{money(r.cost, r.currency)}</span> },
    { header: "订单数", className: NUM, cell: (r) => <span className="tabular-nums">{r.orders.toLocaleString()}</span> },
    // 回本天数 0 = 周期内毛利为负，算不出回本点，显示「—」而不是骗人的 0 天。
    { header: "回本天数", className: NUM, cell: (r) => <span className="tabular-nums">{r.payback > 0 ? Math.round(r.payback) : "—"}</span> },
    { header: "ROI", className: NUM, cell: (r) => <GradedRate text={`${(r.roi * 100).toFixed(0)}%`} grade={roiGrade(r.roi)} /> },
  ];

  const financeCols: Column<ReportFinance>[] = [
    { header: "周期", cell: (r) => <span className="font-medium">{r.period}</span> },
    { header: "GMV", className: NUM, cell: (r) => <span className="tabular-nums">{money(r.gmv, r.currency)}</span> },
    { header: "分润", className: NUM, cell: (r) => <span className="tabular-nums">{money(r.share, r.currency)}</span> },
    { header: "结算", className: NUM, cell: (r) => <span className="tabular-nums">{money(r.settle, r.currency)}</span> },
    { header: "净收入", className: NUM, cell: (r) => <span className="tabular-nums font-medium">{money(r.net, r.currency)}</span> },
  ];

  const customCols: Column<WideRow>[] = [
    { header: REPORT_CUSTOM_DIMS.find((d) => d.value === dim)?.label ?? "维度", cell: (r) => <span className="font-medium">{r.dim}</span> },
    ...pickedMetrics.map((m) => ({
      header: m.label,
      className: NUM,
      cell: (r: WideRow) => <span className="tabular-nums">{fmtMetric(r.values[m.key] ?? 0, m.format)}</span>,
    })),
  ];

  // 导出：文件名带周期，否则下载一堆同名 CSV 分不清是哪段时间的。
  const exportCurrent = () => {
    const suffix = `-${periodLabel(period)}`;
    if (tab === "device") {
      exportCsv<ReportDevice>(`设备运营分析${suffix}`, [
        { header: "站点", value: (r) => r.locationName },
        { header: "机柜数", value: (r) => Math.round(r.cabinetCount) },
        { header: "订单数", value: (r) => r.orders },
        { header: "在线率", value: (r) => r.onlineRate },
        { header: "翻台率", value: (r) => r.turnover },
        { header: "故障率", value: (r) => r.faultRate },
      ], (q.data?.list ?? []) as ReportDevice[]);
    } else if (tab === "location") {
      exportCsv<ReportLocation>(`点位坪效${suffix}`, [
        { header: "站点", value: (r) => r.siteName },
        { header: "营收", value: (r) => r.revenue },
        { header: "成本", value: (r) => r.cost },
        { header: "订单数", value: (r) => r.orders },
        { header: "币种", value: (r) => r.currency },
        { header: "回本天数", value: (r) => (r.payback > 0 ? Math.round(r.payback) : "") },
        { header: "ROI", value: (r) => r.roi },
      ], (q.data?.list ?? []) as ReportLocation[]);
    } else {
      exportCsv<ReportFinance>(`财务报表${suffix}`, [
        { header: "周期", value: (r) => r.period },
        { header: "GMV", value: (r) => r.gmv },
        { header: "分润", value: (r) => r.share },
        { header: "结算", value: (r) => r.settle },
        { header: "净收入", value: (r) => r.net },
        { header: "币种", value: (r) => r.currency },
      ], (q.data?.list ?? []) as ReportFinance[]);
    }
  };

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {isPeriodTab && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); paging.reset(); }}
            searchPlaceholder={tab === "finance" ? "搜索周期" : "搜索站点"}
            // 三张报表共用一条工具条：周期选择在左，导出按当前 tab 出对应的列
            onExport={exportCurrent}
          >
            <PeriodSelect value={period} onChange={(v) => { setPeriod(v as ReportPeriod); paging.reset(); }} />
          </Toolbar>
          <Notice>
            统计口径：{periodLabel(period)}（到昨日为止，T+1 跑批）。图表与汇总条与表格同源，切周期三处同时变；
            「实时大屏」才是今日到点的实时数。
          </Notice>
          <SummaryBar trend={trendQ.data} />
          <TrendChart trend={trendQ.data} loading={trendQ.isLoading} />
        </>
      )}

      {tab === "device" && (
        <DataTable
          rowKey={(r: ReportDevice) => r.locationName}
          columns={deviceCols}
          rows={q.data?.list as ReportDevice[]}
          loading={q.isLoading} error={q.error} onRetry={q.refetch}
          empty="暂无设备运营数据——点位投放并产生订单后按日汇总，或放宽搜索条件/换个周期再查"
        />
      )}
      {tab === "location" && (
        <DataTable
          rowKey={(r: ReportLocation) => r.siteName}
          columns={locationCols}
          rows={q.data?.list as ReportLocation[]}
          loading={q.isLoading} error={q.error} onRetry={q.refetch}
          empty="暂无坪效数据——站点需先录入投入成本并有营收记录，才能算回本天数与 ROI"
        />
      )}
      {tab === "finance" && (
        <DataTable
          rowKey={(r: ReportFinance) => r.period}
          columns={financeCols}
          rows={q.data?.list as ReportFinance[]}
          loading={q.isLoading} error={q.error} onRetry={q.refetch}
          empty="暂无财务报表——按周期跑批汇总 GMV/分润/结算，本周期尚未出数"
        />
      )}

      {tab === "screen" && <ScreenBoardView />}

      {tab === "consumer" && (
        <>
          <ConsumerInsightView insight={insightQ.data} />
          <Toolbar
            onExport={() => exportCsv<ConsumerSegment>("消费者分析", [
              { header: "人群", value: (r) => r.segment },
              { header: "用户数", value: (r) => r.userCount },
              { header: "复借率", value: (r) => r.repeatRate },
              { header: "客单价", value: (r) => r.avgOrderValue },
              { header: "币种", value: (r) => r.currency },
            ], (q.data?.list ?? []) as ConsumerSegment[])}
          />
          <DataTable
            rowKey={(r: ConsumerSegment) => r.segmentNo}
            columns={consumerCols}
            rows={q.data?.list as ConsumerSegment[]}
            loading={q.isLoading} error={q.error} onRetry={q.refetch}
            empty="暂无人群分析——需累积一定量的订单与用户行为后才会分层，新上线阶段属正常"
          />
        </>
      )}

      {tab === "custom" && (
        <>
          {/* 维度 + 自选指标 + 周期同处工具条：导出的就是当前这张（宽表）可见结果。 */}
          <Toolbar
            onExport={() => exportCsv<WideRow>(`自定义报表-${periodLabel(period)}`, [
              { header: REPORT_CUSTOM_DIMS.find((d) => d.value === dim)?.label ?? "维度", value: (r) => r.dim },
              ...pickedMetrics.map((m) => ({ header: m.label, value: (r: WideRow) => r.values[m.key] ?? 0 })),
            ], wideRows)}
          >
            <span className="text-sm text-muted-foreground">维度</span>
            <FilterSelect
              className="w-32"
              aria-label="统计维度"
              value={dim}
              onChange={(v) => setDim(v)}
              options={REPORT_CUSTOM_DIMS.map((d) => ({ value: d.value, label: d.label }))}
            />
            <span className="text-sm text-muted-foreground">指标</span>
            <MultiSelect
              className="w-72"
              value={metrics}
              options={catalog.map((m) => ({ value: m.key, label: m.label }))}
              onChange={setMetrics}
              placeholder="选择要统计的指标"
            />
            <PeriodSelect value={period} onChange={(v) => setPeriod(v as ReportPeriod)} />
          </Toolbar>

          {metrics.length === 0 ? (
            <Notice>请至少勾选一个指标——自定义报表的列由你选的指标决定，没选就无从统计。</Notice>
          ) : (
            <>
              {chartMetric && (
                <Card className="mb-4">
                  <CardHeader><CardTitle>{`${chartMetric.label} · 按${REPORT_CUSTOM_DIMS.find((d) => d.value === dim)?.label ?? "维度"}`}</CardTitle></CardHeader>
                  <CardContent>
                    {/* 图只画第一个指标：一张图叠 8 个量纲不同的指标读不出任何东西。 */}
                    <div className="h-60 w-full">
                      <ResponsiveContainer width="100%" height="100%">
                        <BarChart
                          data={wideRows.map((r) => ({ dim: r.dim, value: r.values[chartMetric.key] ?? 0 }))}
                          margin={{ left: -12, right: 8, top: 8 }}
                        >
                          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                          <XAxis dataKey="dim" {...AXIS} interval={0} angle={-12} textAnchor="end" height={48} />
                          <YAxis {...AXIS} />
                          <Tooltip contentStyle={TIP_STYLE} />
                          <Bar dataKey="value" name={chartMetric.label} fill="var(--primary)" radius={[4, 4, 0, 0]} isAnimationActive={false} />
                        </BarChart>
                      </ResponsiveContainer>
                    </div>
                  </CardContent>
                </Card>
              )}
              <DataTable
                rowKey={(r: WideRow) => r.dim}
                columns={customCols}
                rows={wideRows}
                loading={customQ.isLoading} error={customQ.error} onRetry={customQ.refetch}
                empty="暂无数据——换个维度/周期，或减少指标再查"
              />
            </>
          )}
        </>
      )}

      {isPeriodTab && q.data && (
        <Pagination page={paging.page} size={paging.size} total={q.data.total} onPage={paging.setPage} onSize={paging.setSize} />
      )}
    </div>
  );
}

export default function ReportsPage() {
  return <Suspense fallback={null}><ReportsInner /></Suspense>;
}
