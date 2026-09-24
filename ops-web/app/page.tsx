"use client";

import { useQuery } from "@tanstack/react-query";
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";
import Link from "next/link";
import { api } from "@/lib/api";
import { StatCard, PageTitle, Skeleton } from "@/components/ui/misc";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { QuickActions } from "@/components/quick-actions";
import { money } from "@/lib/utils";
import { usePortalTitle } from "@/lib/hooks/use-portal-title";
import type { DashboardAlert } from "@/lib/types";

// 告警类型 → 文案 + 色调。原为就地三目（类型判两遍：一遍出色、一遍出字），
// 两处各改一半就会出现「红底写着超时」。键序 = 严重度从高到低。
const ALERT_TYPE: StatusMap<DashboardAlert["type"]> = {
  OFFLINE: { label: "离线", tone: "danger" },
  EXCEPTION: { label: "异常", tone: "warning" },
  TIMEOUT: { label: "超时", tone: "muted" },
};

export default function DashboardPage() {
  const { data, isLoading } = useQuery({ queryKey: ["dashboard"], queryFn: () => api.getDashboard() });
  // 拍板 #5：AGENT 门户下标题换「我的看板」（本页无 tab 概念，恒视为默认位）
  const portalTitle = usePortalTitle(null, true);

  return (
    <div>
      {/* 快捷动作放页头：看板是每个角色的落地页，「看到问题→当场处置」不该先跳三层页面。
          按钮各按底层操作的权限码显示，无权限的角色看不到（见 components/quick-actions）。 */}
      <PageTitle title={portalTitle ?? "工作台"} desc="经营总览（今日）" action={<QuickActions />} />
      {isLoading || !data ? (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
          {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-24" />)}
        </div>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
            {/*
              2026-09-24：撤掉两张卡片上写死的 `+8.2% 环比` / `+5.1% 环比`。
              它们是**字面量**，不是算出来的 —— 首屏最显眼的两个经营指标是假的，
              而看的人会拿它做决策。**假数据比缺数据危险**：缺了看得出来，假的看不出来。

              为什么不顺手用 trend 算一个：后端 `trend` 里昨天那个点是**全天**，
              而 `gmvToday` 是**今天到此刻**。半天比全天，每天早上都会显示
              「-60% 环比」—— 那比没有更糟，因为它看起来像在报警。
              要算对需要「昨日同时刻」的口径，后端目前不提供（见 ReportServiceImpl 的口径说明）。

              补法：后端 DashboardStats 增加 `gmvSameTimeYesterday` / `ordersSameTimeYesterday`，
              前端再显示。在那之前宁可不显示。
            */}
            <StatCard label="今日 GMV" value={money(data.gmvToday, data.currency)} />
            <StatCard label="今日订单" value={data.ordersToday} />
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
                    {/* isAnimationActive={false} 是必需的，不是调优：recharts 2.x 的入场动画在
                        React 19 下不推进，路径停在 t=0 —— 折线一根都画不出来。整站 recharts
                        系列都必须带这个 flag（app/reports/page.tsx 同）。升级 recharts 后可复查。 */}
                    <Line type="monotone" dataKey="gmv" stroke="var(--primary)" strokeWidth={2} dot={false} name="GMV" isAnimationActive={false} />
                    <Line type="monotone" dataKey="orders" stroke="var(--success)" strokeWidth={2} dot={false} name="订单" isAnimationActive={false} />
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
                      <StatusBadge map={ALERT_TYPE} value={a.type} />
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
                      <td className="py-2 txt-strong tabular-nums text-muted-foreground">#{r.rank}</td>
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
