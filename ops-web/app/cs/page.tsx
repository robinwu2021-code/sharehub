"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Pagination } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import type { CsTicket, CsSession, PageResult } from "@/lib/types";

const SIZE = 10;
const TABS = [
  { key: "tickets", label: "报障受理" },
  { key: "sessions", label: "客服会话" },
];

const TICKET_FIELDS: FieldDef[] = [
  { key: "ticketNo", label: "工单号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "userNo", label: "用户", placeholder: "U-xxxx" },
  { key: "cabinetNo", label: "设备", placeholder: "机柜号" },
  { key: "issue", label: "问题描述" },
  { key: "channel", label: "渠道", placeholder: "APP / 电话 / 微信" },
  { key: "status", label: "状态", type: "select", options: [
    { value: "OPEN", label: "待处理" },
    { value: "PROCESSING", label: "处理中" },
    { value: "CLOSED", label: "已关闭" },
  ] },
];

const TICKET_STATUS: Record<CsTicket["status"], { label: string; tone: "muted" | "outline" | "warning" | "success" }> = {
  OPEN: { label: "待处理", tone: "warning" },
  PROCESSING: { label: "处理中", tone: "outline" },
  CLOSED: { label: "已关闭", tone: "muted" },
};
const SESSION_STATUS: Record<CsSession["status"], { label: string; tone: "success" | "muted" }> = {
  ACTIVE: { label: "进行中", tone: "success" },
  CLOSED: { label: "已结束", tone: "muted" },
};

function CsInner() {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const qc = useQueryClient();
  const { t } = useI18n();
  const [tab, setTab] = useState(TABS.some((x) => x.key === qTab) ? (qTab as string) : "tickets");
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [ticketForm, setTicketForm] = useState<Partial<CsTicket> | null>(null);
  const allow = useCan();
  useEffect(() => { if (qTab && TABS.some((x) => x.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);

  const canEditTicket = allow("cs:ticket:handle");
  const saveTicket = useMutation({
    mutationFn: (x: Partial<CsTicket>) => api.saveCsTicket(x),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["cs"] }); notify.success(t("common.success")); setTicketForm(null); },
  });

  const q = useQuery<PageResult<CsTicket | CsSession>>({
    queryKey: ["cs", tab, page, keyword],
    queryFn: () =>
      tab === "sessions" ? api.listCsSessions({ page, size: SIZE, keyword })
      : api.listCsTickets({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
  });

  const ticketCols: Column<CsTicket>[] = [
    { header: "工单号", cell: (t) => <span className="font-medium">{t.ticketNo}</span> },
    { header: "用户", cell: (t) => <span className="text-muted-foreground">{t.userNo}</span> },
    { header: "设备", cell: (t) => <span className="text-muted-foreground">{t.cabinetNo}</span> },
    { header: "问题", cell: (t) => t.issue },
    { header: "渠道", cell: (t) => <Badge tone="outline">{t.channel}</Badge> },
    { header: "状态", cell: (t) => <Badge tone={TICKET_STATUS[t.status].tone}>{TICKET_STATUS[t.status].label}</Badge> },
    { header: "创建时间", cell: (t) => <span className="text-muted-foreground">{fmtTime(t.createdAt)}</span> },
    {
      header: "操作",
      cell: (row) => canEditTicket ? (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setTicketForm(row)}>{t("common.edit")}</Button>
          {row.status !== "CLOSED" && <>
            <Button size="sm" variant="outline" onClick={() => alert(`已为 ${row.ticketNo} 创建维修工单（mock）`)}>转工单</Button>
            <Button size="sm" variant="outline" onClick={() => alert(`已为 ${row.ticketNo} 发起退款审核（mock）`)}>转退款</Button>
          </>}
        </div>
      ) : <span className="text-muted-foreground">-</span>,
    },
  ];

  const sessionCols: Column<CsSession>[] = [
    { header: "会话号", cell: (s) => <span className="font-medium">{s.sessionNo}</span> },
    { header: "用户", cell: (s) => <span className="text-muted-foreground">{s.userNo}</span> },
    { header: "客服", cell: (s) => s.agentName },
    { header: "最新消息", cell: (s) => <span className="text-muted-foreground">{s.lastMessage}</span> },
    { header: "状态", cell: (s) => <Badge tone={SESSION_STATUS[s.status].tone}>{SESSION_STATUS[s.status].label}</Badge> },
    { header: "更新时间", cell: (s) => <span className="text-muted-foreground">{fmtTime(s.updatedAt)}</span> },
  ];

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); }} />
      {tab === "tickets" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索工单号 / 用户 / 设备 / 问题"
          onAdd={canEditTicket ? () => setTicketForm({ status: "OPEN", channel: "APP", issue: "" }) : undefined}
          addLabel="新增工单"
        />
      )}
      {tab === "sessions" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索会话号 / 用户 / 客服"
        />
      )}
      {tab === "tickets" && <DataTable rowKey={(t: CsTicket) => t.ticketNo} columns={ticketCols} rows={q.data?.list as CsTicket[]} loading={q.isLoading} />}
      {tab === "sessions" && <DataTable rowKey={(s: CsSession) => s.sessionNo} columns={sessionCols} rows={q.data?.list as CsSession[]} loading={q.isLoading} />}
      {q.data && <Pagination page={page} size={SIZE} total={q.data.total} onPage={setPage} />}

      <FormDrawer
        open={!!ticketForm}
        onOpenChange={(o) => !o && setTicketForm(null)}
        titleNew="新增工单"
        titleEdit={`编辑工单 ${ticketForm?.ticketNo ?? ""}`}
        isEdit={!!ticketForm?.ticketNo}
        fields={TICKET_FIELDS}
        value={(ticketForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setTicketForm(v as Partial<CsTicket>)}
        onSubmit={() => ticketForm && saveTicket.mutate(ticketForm)}
        submitting={saveTicket.isPending}
      />
    </div>
  );
}

export default function CsPage() {
  return <Suspense fallback={null}><CsInner /></Suspense>;
}
