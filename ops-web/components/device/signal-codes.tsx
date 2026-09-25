"use client";

// 设备信号字典（只读）。**信号不是告警**：2026-09-25 裁决把设备错误码降为「信号」——
// 它是设备层止损（挂保护）的依据，告警中心只放业务告警。这里给排障的人看：
// 设备报了某个码，系统会自动做什么、什么信号能把它清掉、它最终喂给哪条业务告警。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge } from "@/components/ui/status-badge";
import type { SignalCode } from "@/lib/types";
import { PROTECTION_ACTION } from "./device-maps";

const SCOPE_LABEL: Record<string, string> = { CABINET: "整柜", SLOT: "仓位", POWERBANK: "充电宝" };

function Action({ a }: { a: SignalCode["protectiveAction"] }) {
  if (!a || a === "NONE") return <span className="text-muted-foreground">只记录</span>;
  if (a === "AUTO_EJECT_RETRY") return <span className="txt-caption">自动重弹</span>;
  return <StatusBadge map={PROTECTION_ACTION} value={a} />;
}

const cols: Column<SignalCode>[] = [
  {
    header: "信号码",
    cell: (s) => (
      <div className="min-w-0">
        <div className="tabular-nums">{s.code}</div>
        <div className="txt-caption text-muted-foreground">{s.name}</div>
      </div>
    ),
  },
  { header: "分类", cell: (s) => <span className="text-muted-foreground">{s.category ?? "-"}</span> },
  { header: "作用范围", cell: (s) => (s.scope ? SCOPE_LABEL[s.scope] ?? s.scope : "-") },
  { header: "自动止损", cell: (s) => <Action a={s.protectiveAction} /> },
  { header: "清除", cell: (s) => (s.clearsCode ? <span className="tabular-nums">清掉 {s.clearsCode}</span> : <span className="text-muted-foreground">-</span>) },
  { header: "喂给", cell: (s) => <span className="txt-caption">{s.feeds ?? "-"}</span> },
];

export function SignalCodesTable() {
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["device-signals"],
    queryFn: () => api.listSignalCodes(),
    staleTime: 10 * 60_000,
  });
  return (
    <DataTable
      rowKey={(s: SignalCode) => s.code}
      columns={cols}
      rows={data}
      loading={isLoading}
      error={error}
      onRetry={refetch}
      empty="信号字典为空——设备接入网关上报的信号码没有登记，信号会按「只记录不保护」处理"
    />
  );
}
