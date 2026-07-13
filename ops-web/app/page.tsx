"use client";

import { useQuery } from "@tanstack/react-query";
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";
import { api } from "@/lib/api";
import { StatCard, PageTitle, Skeleton } from "@/components/ui/misc";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
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
        </>
      )}
    </div>
  );
}
