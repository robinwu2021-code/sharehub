"use client";

import { Suspense, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Skeleton, EmptyState } from "@/components/ui/misc";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { CabinetStatusBadge, OnlineBadge } from "@/components/status";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
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
            {canCmd && <Button size="sm" onClick={() => cmd.mutate({ type: "EJECT_ANY" })} disabled={cmd.isPending}>弹出充电宝</Button>}
          </div>
        }
      />
      {msg && <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm">{msg}</div>}

      {isLoading || !data ? (
        <Skeleton className="h-40" />
      ) : (
        <>
          <Card>
            <CardContent className="grid grid-cols-2 gap-4 pt-5 text-sm md:grid-cols-4">
              <Field label="点位" v={data.cabinet.locationName} />
              <Field label="供应商" v={data.cabinet.vendorCode} />
              <Field label="型号" v={data.cabinet.model} />
              <Field label="固件" v={data.cabinet.fwVersion} />
              <Field label="在线" v={<OnlineBadge s={data.cabinet.onlineStatus} />} />
              <Field label="状态" v={<CabinetStatusBadge s={data.cabinet.status} />} />
              <Field label="可借/仓位" v={`${data.cabinet.availableCount} / ${data.cabinet.slotTotal}`} />
              <Field label="最后心跳" v={fmtTime(data.cabinet.lastHeartbeatAt)} />
            </CardContent>
          </Card>

          <Card className="mt-6">
            <CardHeader><CardTitle>仓位明细</CardTitle></CardHeader>
            <CardContent>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
                {data.slots.map((s) => (
                  <div key={s.slotIndex} className="rounded-xl bg-muted/60 p-3">
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
                        onClick={() => cmd.mutate({ type: "EJECT_SLOT", slotIndex: s.slotIndex })}>
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

function Field({ label, v }: { label: string; v: React.ReactNode }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-1">{v}</div>
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
