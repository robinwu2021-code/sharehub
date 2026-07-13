"use client";

import { useQuery } from "@tanstack/react-query";
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";
import Link from "next/link";
import { api } from "@/lib/api";
import { StatCard, PageTitle, Skeleton } from "@/components/ui/misc";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { money } from "@/lib/utils";

export default function DashboardPage() {
  const { data, isLoading } = useQuery({ queryKey: ["dashboard"], queryFn: () => api.getDashboard() });

  return (
    <div>
      <PageTitle title="工作台" desc="经营总览（今日）" />
      {isLoading || !data ? (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
          {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-24" />)}
        </div>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
            <StatCard label="今日 GMV" value={money(data.gmvToday, data.currency)} sub="+8.2% 环比" />
            <StatCard label="今日订单" value={data.ordersToday} sub="+5.1% 环比" />
            <StatCard label="在线设备" value={data.activeCabinets} />
            <StatCard label="设备在线率" value={`${(data.onlineRate * 100).toFixed(1)}%`} sub={data.onlineRate < 0.9 ? "低于目标" : "达标"} tone={data.onlineRate < 0.9 ? "down" : "up"} />
            <StatCard label="待处理工单" value={data.openWorkOrders} />
          </div>

          <Card className="mt-6">
            <CardHeader><CardTitle>近 7 日 GMV / 订单趋势</CardTitle></CardHeader>
            <CardContent>
              <div className="h-72 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={data.trend} margin={{ left: -12, right: 8, top: 8 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                    <XAxis dataKey="day" stroke="var(--muted-foreground)" fontSize={12} />
                    <YAxis stroke="var(--muted-foreground)" fontSize={12} />
                    <Tooltip contentStyle={{ background: "var(--card)", border: "1px solid var(--border)", borderRadius: 8, fontSize: 12 }} />
                    <Line type="monotone" dataKey="gmv" stroke="var(--primary)" strokeWidth={2} dot={false} name="GMV" />
                    <Line type="monotone" dataKey="orders" stroke="var(--success)" strokeWidth={2} dot={false} name="订单" />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </CardContent>
          </Card>

          {/* 待办中心 */}
          <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Link href="/work-orders?view=list">
              <Card className="hover:border-primary/50 transition-colors cursor-pointer">
                <CardContent className="pt-5">
                  <p className="text-muted-foreground text-sm">待处理工单</p>
                  <p className="text-3xl font-bold tabular-nums mt-1">{data.todos.pendingWorkOrders}</p>
                </CardContent>
              </Card>
            </Link>
            <Link href="/orders?tab=exceptions">
              <Card className="hover:border-primary/50 transition-colors cursor-pointer">
                <CardContent className="pt-5">
                  <p className="text-muted-foreground text-sm">待退款/补偿</p>
                  <p className="text-3xl font-bold tabular-nums mt-1">{data.todos.pendingRefunds}</p>
                </CardContent>
              </Card>
            </Link>
            <Link href="/finance?tab=withdrawals">
              <Card className="hover:border-primary/50 transition-colors cursor-pointer">
                <CardContent className="pt-5">
                  <p className="text-muted-foreground text-sm">待审提现</p>
                  <p className="text-3xl font-bold tabular-nums mt-1">{data.todos.pendingWithdrawals}</p>
                </CardContent>
              </Card>
            </Link>
          </div>

          {/* 实时告警 */}
          {data.alerts.length > 0 && (
            <Card className="mt-6">
              <CardHeader><CardTitle>实时告警</CardTitle></CardHeader>
              <CardContent>
                <ul className="space-y-2">
                  {data.alerts.map((a) => (
                    <li key={a.id} className="flex items-center gap-3 text-sm">
                      <Badge tone={a.type === "OFFLINE" ? "danger" : a.type === "EXCEPTION" ? "warning" : "muted"}>
                        {a.type === "OFFLINE" ? "离线" : a.type === "EXCEPTION" ? "异常" : "超时"}
                      </Badge>
                      <span className="flex-1">{a.message}</span>
                      <Link href={a.href} className="text-primary hover:underline shrink-0">查看</Link>
                    </li>
                  ))}
                </ul>
              </CardContent>
            </Card>
          )}

          {/* 排名榜单 */}
          <Card className="mt-6">
            <CardHeader><CardTitle>今日站点 GMV 排名</CardTitle></CardHeader>
            <CardContent>
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground text-left border-b border-border">
                    <th className="pb-2 w-8">排名</th>
                    <th className="pb-2">站点</th>
                    <th className="pb-2 text-right tabular-nums">GMV</th>
                    <th className="pb-2 text-right tabular-nums">订单数</th>
                  </tr>
                </thead>
                <tbody>
                  {data.rankings.map((r) => (
                    <tr key={r.rank} className="border-b border-border/40 last:border-0">
                      <td className="py-2 font-medium tabular-nums text-muted-foreground">#{r.rank}</td>
                      <td className="py-2">{r.siteName}</td>
                      <td className="py-2 text-right tabular-nums">{money(r.gmv, r.currency)}</td>
                      <td className="py-2 text-right tabular-nums">{r.orderCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
