"use client";

// 商家营收首页（商家一期 B5，需求方案 §10.2）。商家门户的落地页就是营收：
// 核心数字 → 钱的状态 → 趋势 → 设备排行。口径在后端 MerchantRevenueController，前端只负责摆。
//
// 数据范围由服务端硬过滤（商家只看到自己的），这里不传 agentNo —— 传了也不该被信任。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";
import { api } from "@/lib/api";
import { StatCard, Skeleton } from "@/components/ui/misc";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, THead, TBody, TR, TH, TD } from "@/components/ui/table";
import { DateInput } from "@/components/ui/date-input";
import { RefLink } from "@/components/ref-link";
import { segmentedItemClass, segmentedTrackClass } from "@/components/ui/segmented";
import { money } from "@/lib/utils";

type Preset = "today" | "yesterday" | "week" | "month" | "lastMonth" | "custom";
const PRESETS: { key: Preset; label: string }[] = [
  { key: "today", label: "今日" },
  { key: "yesterday", label: "昨日" },
  { key: "week", label: "本周" },
  { key: "month", label: "本月" },
  { key: "lastMonth", label: "上月" },
  { key: "custom", label: "自定义" },
];

/** 本地日期 → YYYY-MM-DD。不用 toISOString：那是 UTC，迪拜凌晨 0~4 点会算成前一天。 */
const ymd = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

function presetWindow(p: Exclude<Preset, "custom">, now = new Date()): { from: string; to: string } {
  const y = now.getFullYear(), m = now.getMonth(), d = now.getDate();
  switch (p) {
    case "today": return { from: ymd(now), to: ymd(now) };
    case "yesterday": { const t = ymd(new Date(y, m, d - 1)); return { from: t, to: t }; }
    // 周一起算（阿联酋工作周自 2022 年起为周一至周五）
    case "week": return { from: ymd(new Date(y, m, d - ((now.getDay() + 6) % 7))), to: ymd(now) };
    case "month": return { from: ymd(new Date(y, m, 1)), to: ymd(now) };
    case "lastMonth": return { from: ymd(new Date(y, m - 1, 1)), to: ymd(new Date(y, m, 0)) };
  }
}

/**
 * 环比文案。上期为 0 时不给百分比 —— 「从 0 到 30」写成 +∞% 或 +100% 都是在编数。
 * 「今日」比的是**昨日全天**（后端上一个等长窗口），早上看会偏低；文案里写明「较上期」，不写「较昨日同时」。
 */
function delta(cur: number, prev: number): { sub?: string; tone?: "up" | "down" } {
  if (!prev) return {};
  const r = (cur - prev) / prev;
  return { sub: `较上期 ${r >= 0 ? "+" : ""}${(r * 100).toFixed(1)}%`, tone: r < 0 ? "down" : "up" };
}

export function MerchantRevenueHome() {
  const [preset, setPreset] = useState<Preset>("month");
  const [custom, setCustom] = useState(() => presetWindow("month"));
  const w = preset === "custom" ? custom : presetWindow(preset);
  const customBad = preset === "custom" && (!custom.from || !custom.to || custom.from > custom.to);

  const enabled = !customBad;
  const summary = useQuery({ queryKey: ["merchant-revenue", "summary", w], queryFn: () => api.getRevenueSummary(w), enabled });
  const trend = useQuery({ queryKey: ["merchant-revenue", "trend", w], queryFn: () => api.getRevenueTrend(w), enabled });
  const devices = useQuery({ queryKey: ["merchant-revenue", "by-device", w], queryFn: () => api.getRevenueByDevice({ ...w, limit: 10 }), enabled });
  // 设备提醒按「现在」，与时间窗口无关，所以不进上面三个查询的键
  const alerts = useQuery({ queryKey: ["merchant-revenue", "alerts"], queryFn: () => api.getRevenueAlerts() });
  const s = summary.data;
  const cur = "AED";

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        <div className={segmentedTrackClass("flex-wrap")} role="tablist" aria-label="时间范围">
          {PRESETS.map((p) => (
            <button key={p.key} type="button" role="tab" aria-selected={preset === p.key}
              className={segmentedItemClass(preset === p.key, "px-3 py-1 text-sm")} onClick={() => setPreset(p.key)}>
              {p.label}
            </button>
          ))}
        </div>
        {preset === "custom" && (
          <div className="flex w-full items-center gap-2 sm:w-auto">
            <DateInput aria-label="起始日期" className="min-w-0 flex-1 sm:w-40 sm:flex-none" value={custom.from} max={custom.to || undefined}
              onChange={(e) => setCustom((c) => ({ ...c, from: e.target.value }))} />
            <span className="text-muted-foreground">至</span>
            <DateInput aria-label="截止日期" className="min-w-0 flex-1 sm:w-40 sm:flex-none" value={custom.to} min={custom.from || undefined}
              onChange={(e) => setCustom((c) => ({ ...c, to: e.target.value }))} />
          </div>
        )}
        <span className="txt-caption text-muted-foreground">{w.from === w.to ? w.from : `${w.from} 至 ${w.to}`}</span>
      </div>

      {customBad ? (
        <p className="txt-body text-muted-foreground">请选择起止日期，截止日期不能早于起始日期，跨度最长一年。</p>
      ) : summary.isError ? (
        <p className="txt-body text-[var(--destructive)]">{(summary.error as Error).message}</p>
      ) : !s ? (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-24" />)}
        </div>
      ) : (
        <>
          {/* 核心数字：交易额含 VAT、税前营收已扣 VAT —— 两个数并排放，差额就是税，商家一眼能对上 */}
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
            <StatCard label="交易额（含税）" value={money(s.gross, cur)} {...delta(s.gross, s.prevGross)} />
            <StatCard label="订单数" value={s.orderCount} {...delta(s.orderCount, s.prevOrderCount)} />
            <StatCard label="税前营收" value={money(s.preTax, cur)} />
            <StatCard label="我的分成" value={money(s.myShare, cur)} {...delta(s.myShare, s.prevMyShare)} />
          </div>
          {s.refund > 0 && (
            <p className="txt-caption text-muted-foreground">
              本期退款 {money(s.refund, cur)}，净交易额 {money(s.net, cur)}。
            </p>
          )}

          {/* 钱的状态按「现在」，不随上面的时间范围变 —— 标题里写明，免得切到「昨日」后以为待结算也是昨天的 */}
          <Card>
            <CardHeader><CardTitle>钱的状态（截至现在）</CardTitle></CardHeader>
            <CardContent>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                <MoneyLink href="/settlement?tab=records" label="待结算" hint="已产生分成、还没进已确认的结算单" value={money(s.pending, cur)} />
                <MoneyLink href="/settlement?tab=settlements" label="已结算未付" hint="结算单已确认，等平台转账" value={money(s.settledUnpaid, cur)} />
                <MoneyLink href="/settlement?tab=withdrawals" label="已到账" hint="平台已确认转账完成" value={money(s.paid, cur)} />
              </div>
            </CardContent>
          </Card>
        </>
      )}

      {/* 设备提醒（§10.2）：离线、在线却借不出 —— 两件都在直接少收钱。点柜号进详情处理；都没有就不占地方 */}
      {alerts.data && (alerts.data.offline > 0 || alerts.data.noBorrowable > 0) && (
        <Card>
          <CardHeader><CardTitle>需要处理的设备（截至现在）</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <AlertGroup label="离线" hint="设备连不上，用户借不了也还不了" count={alerts.data.offline} nos={alerts.data.offlineSample} />
            <AlertGroup label="无可借电池" hint="在线但没有可借的充电宝，需要补货" count={alerts.data.noBorrowable} nos={alerts.data.noBorrowableSample} />
          </CardContent>
        </Card>
      )}

      {!customBad && (
        <Card>
          <CardHeader><CardTitle>按天交易额与订单数</CardTitle></CardHeader>
          <CardContent>
            {trend.data && trend.data.some((d) => d.orderCount > 0) ? (
              <div className="h-72 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={trend.data} margin={{ left: -12, right: 8, top: 8 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                    <XAxis dataKey="date" stroke="var(--muted-foreground)" fontSize={12} tickFormatter={(d: string) => d.slice(5)} />
                    <YAxis stroke="var(--muted-foreground)" fontSize={12} />
                    <Tooltip contentStyle={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: 8, fontSize: 12 }} />
                    {/* isAnimationActive={false} 必需：recharts 2.x 入场动画在 React 19 下不推进（见 app/page.tsx） */}
                    <Line type="monotone" dataKey="gross" stroke="var(--primary)" strokeWidth={2} dot={false} name="交易额" isAnimationActive={false} />
                    <Line type="monotone" dataKey="orderCount" stroke="var(--success)" strokeWidth={2} dot={false} name="订单数" isAnimationActive={false} />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            ) : trend.data ? (
              <p className="txt-body text-muted-foreground">这段时间还没有完成的订单。设备上线并有人借还后，这里按天显示交易额。</p>
            ) : (
              <Skeleton className="h-72" />
            )}
          </CardContent>
        </Card>
      )}

      {!customBad && (
        <Card>
          <CardHeader><CardTitle>设备交易额排行</CardTitle></CardHeader>
          <CardContent>
            {devices.data && devices.data.length > 0 ? (
              <Table>
                <THead>
                  <TR>
                    <TH>设备</TH>
                    <TH className="text-right">订单数</TH>
                    <TH className="text-right">交易额</TH>
                  </TR>
                </THead>
                <TBody>
                  {devices.data.map((d) => (
                    <TR key={d.cabinetNo}>
                      <TD className="tabular-nums">{d.cabinetNo}</TD>
                      <TD className="text-right tabular-nums">{d.orderCount}</TD>
                      <TD className="text-right tabular-nums">{money(d.gross, cur)}</TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            ) : devices.data ? (
              <p className="txt-body text-muted-foreground">设备上线后这里会显示每台设备的交易额。</p>
            ) : (
              <Skeleton className="h-40" />
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function AlertGroup({ label, hint, count, nos }: { label: string; hint: string; count: number; nos: string[] }) {
  return (
    <div className="rounded-card border border-border p-4">
      <p className="txt-body text-muted-foreground">{label}</p>
      <p className="mt-1 txt-display tabular-nums">{count} 台</p>
      <p className="mt-1 txt-caption text-muted-foreground">{hint}</p>
      {count > 0 && (
        <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1">
          {nos.map((no) => <RefLink key={no} kind="cabinet" no={no} />)}
          {count > nos.length && <span className="txt-caption text-muted-foreground">等 {count} 台</span>}
        </div>
      )}
    </div>
  );
}

function MoneyLink({ href, label, hint, value }: { href: string; label: string; hint: string; value: string }) {
  return (
    <Link href={href} className="block rounded-card border border-border p-4 transition-colors hover:border-primary/50">
      <p className="txt-body text-muted-foreground">{label}</p>
      <p className="mt-1 txt-display tabular-nums">{value}</p>
      <p className="mt-1 txt-caption text-muted-foreground">{hint}</p>
    </Link>
  );
}
