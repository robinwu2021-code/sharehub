"use client";

import { Suspense, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Skeleton, EmptyState } from "@/components/ui/misc";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { CabinetStatusBadge, OnlineBadge } from "@/components/status";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { ChevronLeft, RotateCw } from "lucide-react";

// 静态导出（output: export）用 query 参数而非动态段（对齐 ai-boss/ops-web 的 /detail 模式）。
function CabinetDetail() {
  const cabinetNo = useSearchParams().get("no") ?? "";
  const canCmd = useCan()("device:command:send");
  const { data, isLoading, refetch } = useQuery({
    queryKey: ["cabinet", cabinetNo],
    queryFn: () => api.getCabinet(cabinetNo),
    enabled: !!cabinetNo,
  });
  const [msg, setMsg] = useState("");

  const cmd = useMutation({
    mutationFn: (v: { type: string; slotIndex?: number }) => api.sendCommand(cabinetNo, v.type, v.slotIndex ? { slotIndex: v.slotIndex } : undefined),
    onSuccess: (r) => setMsg(`指令已下发：${r.commandId}`),
    onError: (e: Error) => setMsg(`失败：${e.message}`),
  });

  if (!cabinetNo) return <EmptyState title="缺少柜机号" />;

  return (
    <div>
      <Link href="/devices" className="mb-2 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ChevronLeft className="size-4" /> 返回设备列表
      </Link>
      <PageTitle
        title={`柜机 ${cabinetNo}`}
        action={
          <div className="flex gap-2">
            <Button size="sm" variant="outline" onClick={() => refetch()}><RotateCw className="size-4" /> 刷新</Button>
            {canCmd && <Button size="sm" variant="outline" onClick={() => cmd.mutate({ type: "REBOOT" })} disabled={cmd.isPending}>重启</Button>}
            {/* 指令名走 types 层的 COMMAND_TYPES 词表：原先这里发的 EJECT_ANY / EJECT_SLOT
                不在 CommandRecord.type 里，下发完在指令记录 tab 显示成空白类型。
                收敛后「不带仓位 = 任意仓、带仓位 = 指定仓」，一个 EJECT 表达两件事 */}
            {canCmd && <Button size="sm" onClick={() => cmd.mutate({ type: "EJECT" })} disabled={cmd.isPending}>弹出充电宝</Button>}
          </div>
        }
      />
      {msg && <div className="mb-4 rounded-card bg-muted px-3.5 py-2 text-sm">{msg}</div>}

      {isLoading || !data ? (
        <Skeleton className="h-40" />
      ) : (
        <>
          <Card>
            <CardContent className="grid grid-cols-2 gap-4 pt-5 text-sm md:grid-cols-4">
              <Field label="点位" className="mb-0">{data.cabinet.locationName}</Field>
              {/* 归属站点（偏差 A1）：站点号 + 点位号一起给，排障时能一路查到站点与合同；
                  未上架或后端未提供该列时显示「未归属」，不用点位名冒充站点 */}
              <Field label="归属站点" className="mb-0">
                {data.cabinet.siteNo ?? "未归属"}
                {data.cabinet.locationNo && <span className="text-muted-foreground"> · {data.cabinet.locationNo}</span>}
              </Field>
              <Field label="供应商" className="mb-0">{data.cabinet.vendorCode}</Field>
              <Field label="型号" className="mb-0">{data.cabinet.model}</Field>
              <Field label="固件" className="mb-0">{data.cabinet.fwVersion}</Field>
              <Field label="在线" className="mb-0"><OnlineBadge s={data.cabinet.onlineStatus} /></Field>
              <Field label="状态" className="mb-0"><CabinetStatusBadge s={data.cabinet.status} /></Field>
              <Field label="可借/仓位" className="mb-0">{`${data.cabinet.availableCount} / ${data.cabinet.slotTotal}`}</Field>
              <Field label="最后心跳" className="mb-0">{fmtTime(data.cabinet.lastHeartbeatAt)}</Field>
            </CardContent>
          </Card>

          <Card className="mt-6">
            <CardHeader><CardTitle>仓位明细</CardTitle></CardHeader>
            <CardContent>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
                {data.slots.map((s) => (
                  <div key={s.slotIndex} className="rounded-card bg-muted/60 p-3">
                    <div className="flex items-center justify-between">
                      <span className="text-sm font-medium">仓位 {s.slotIndex}</span>
                      {s.health === "FAULT" ? <Badge tone="danger">故障</Badge> : s.powerbankNo ? <Badge tone="success">有宝</Badge> : <Badge tone="muted">空仓</Badge>}
                    </div>
                    <div className="mt-2 space-y-0.5 text-xs text-muted-foreground">
                      <div>充电宝：{s.powerbankNo ?? "-"}</div>
                      <div>电量：{s.battery != null ? `${s.battery}%` : "-"}</div>
                    </div>
                    {canCmd && (
                      <Button className="mt-2 w-full" size="sm" variant="outline" disabled={!s.powerbankNo || cmd.isPending}
                        onClick={() => cmd.mutate({ type: "EJECT", slotIndex: s.slotIndex })}>
                        弹出
                      </Button>
                    )}
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}

export default function CabinetDetailPage() {
  return (
    <Suspense fallback={<Skeleton className="h-40" />}>
      <CabinetDetail />
    </Suspense>
  );
}
