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
import { Drawer, Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import type { CsTicket, CsSession, CsMessage, PageResult } from "@/lib/types";

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

const TICKET_STATUS: StatusMap<CsTicket["status"]> = {
  OPEN: { label: "待处理", tone: "warning" },
  PROCESSING: { label: "处理中", tone: "outline" },
  CLOSED: { label: "已关闭", tone: "muted" },
};
const SESSION_STATUS: StatusMap<CsSession["status"]> = {
  ACTIVE: { label: "进行中", tone: "success" },
  CLOSED: { label: "已结束", tone: "muted" },
};
// 消息发送方也是枚举，同样进映射表：此前内联三元把「客服=绿 / 用户=灰」的口径写在渲染处，
// 会话列表若哪天也要标发送方就必然抄第二份。
const SENDER: StatusMap<CsMessage["senderType"]> = {
  AGENT: { label: "客服", tone: "success" },
  USER: { label: "用户", tone: "outline" },
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
  const [session, setSession] = useState<CsSession | null>(null);
  const [reply, setReply] = useState("");
  const [attach, setAttach] = useState("");
  const allow = useCan();
  useEffect(() => { if (qTab && TABS.some((x) => x.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);

  const canEditTicket = allow("cs:ticket:handle");
  // 转退款借用资金域的码：谁能发起退款由资金域授权说了算，不是「有客服权限就能造退款单」
  const canRefund = allow("order:refund:apply");
  const canReply = allow("cs:session:reply");
  // 转工单借用工单域的码：谁能开工单由工单域授权说了算
  const canWo = allow("workorder:wo:create");
  const saveTicket = useMutation({
    mutationFn: (x: Partial<CsTicket>) => api.saveCsTicket(x),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["cs"] }); notify.success(t("common.success")); setTicketForm(null); },
  });

  // 报障转退款：后端幂等，重复点只会拿回同一个 refundNo，所以这里不做二次确认
  const toRefund = useMutation({
    mutationFn: (ticketNo: string) => api.refundCsTicket(ticketNo),
    onSuccess: (r) => { notify.success(`已转退款申请 ${r.refundNo}`); qc.invalidateQueries({ queryKey: ["cs"] }); },
  });

  // 报障转工单：后端以 ticketNo 幂等，重复点拿回同一个 woNo，故不做二次确认
  const toWo = useMutation({
    mutationFn: (ticketNo: string) => api.woCsTicket(ticketNo),
    onSuccess: (r) => { notify.success(`已转维修工单 ${r.woNo}`); qc.invalidateQueries({ queryKey: ["cs"] }); },
  });

  // 会话消息时间线：只查当前抽屉打开的那个会话
  const msgQ = useQuery({
    queryKey: ["cs-messages", session?.sessionNo],
    queryFn: () => api.listCsMessages(session!.sessionNo, 200),
    enabled: !!session,
  });
  const doReply = useMutation({
    // 只发 content/attach —— 发送人由后端取登录态，前端不传也不该传
    mutationFn: (v: { no: string; content: string; attach?: string }) =>
      api.replyCsSession(v.no, { content: v.content, attach: v.attach }),
    onSuccess: () => {
      notify.success("回复已发送");
      setReply(""); setAttach("");
      qc.invalidateQueries({ queryKey: ["cs-messages"] });
      qc.invalidateQueries({ queryKey: ["cs"] }); // 列表的「最新消息 / 更新时间」也会变
    },
  });

  const q = useQuery<PageResult<CsTicket | CsSession>>({
    queryKey: ["cs", tab, page, keyword],
    queryFn: () =>
      tab === "sessions" ? api.listCsSessions({ page, size: SIZE, keyword })
      : api.listCsTickets({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
  });

  const ticketCols: Column<CsTicket>[] = [
    // 业务号列是扫描锚点：body-strong（§12.3）+ 等宽数字，整列不跳
    { header: "工单号", cell: (t) => <span className="txt-strong tabular-nums">{t.ticketNo}</span> },
    { header: "用户", cell: (t) => <span className="text-muted-foreground">{t.userNo}</span> },
    { header: "设备", cell: (t) => <span className="text-muted-foreground">{t.cabinetNo}</span> },
    { header: "问题", cell: (t) => t.issue },
    { header: "渠道", cell: (t) => <Badge tone="outline">{t.channel}</Badge> },
    { header: "状态", cell: (t) => <StatusBadge map={TICKET_STATUS} value={t.status} /> },
    { header: "创建时间", cell: (t) => <span className="text-muted-foreground">{fmtTime(t.createdAt)}</span> },
    // 处置去向：已转出的单号直接列出来，点开抽屉才能看到等于查不到
    { header: "退款单", cell: (row) => row.refundNo
      ? <span className="tabular-nums">{row.refundNo}</span>
      : <span className="text-muted-foreground">-</span> },
    {
      header: "操作",
      cell: (row) => (canEditTicket || canRefund) ? (
        <div className="flex gap-2">
          {canEditTicket && <Button size="sm" variant="outline" onClick={() => setTicketForm(row)}>{t("common.edit")}</Button>}
          {canEditTicket && row.status !== "CLOSED" && (
            <Button
              size="sm"
              variant="outline"
              disabled={!canWo || !!row.woNo || toWo.isPending}
              title={row.woNo ? `已转工单 ${row.woNo}` : undefined}
              onClick={() => toWo.mutate(row.ticketNo)}
            >
              {row.woNo ? `工单 ${row.woNo}` : "转工单"}
            </Button>
          )}
          {/* 已转过就不再出按钮：退款单号即幂等凭据，出按钮只会让人以为能再退一笔 */}
          {canRefund && !row.refundNo && (
            <Button
              size="sm" variant="outline"
              disabled={toRefund.isPending || !row.orderNo}
              title={row.orderNo ? undefined : "该报障未关联订单，无从退款"}
              onClick={() => toRefund.mutate(row.ticketNo)}
            >转退款</Button>
          )}
        </div>
      ) : <span className="text-muted-foreground">-</span>,
    },
  ];

  const sessionCols: Column<CsSession>[] = [
    { header: "会话号", cell: (s) => <span className="txt-strong tabular-nums">{s.sessionNo}</span> },
    { header: "用户", cell: (s) => <span className="text-muted-foreground">{s.userNo}</span> },
    { header: "客服", cell: (s) => s.agentName },
    { header: "最新消息", cell: (s) => <span className="text-muted-foreground">{s.lastMessage}</span> },
    { header: "状态", cell: (s) => <StatusBadge map={SESSION_STATUS} value={s.status} /> },
    { header: "更新时间", cell: (s) => <span className="text-muted-foreground">{fmtTime(s.updatedAt)}</span> },
    {
      header: "操作",
      cell: (s) => <Button size="sm" variant="outline" onClick={() => { setSession(s); setReply(""); setAttach(""); }}>会话详情</Button>,
    },
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
          onExport={() => exportCsv<CsTicket>("报障受理", [
            { header: "工单号", value: (x) => x.ticketNo },
            { header: "用户", value: (x) => x.userNo },
            { header: "设备", value: (x) => x.cabinetNo },
            { header: "问题", value: (x) => x.issue },
            { header: "渠道", value: (x) => x.channel },
            { header: "状态", value: (x) => TICKET_STATUS[x.status].label },
            { header: "创建时间", value: (x) => x.createdAt },
          ], (q.data?.list ?? []) as CsTicket[])}
        />
      )}
      {tab === "sessions" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder="搜索会话号 / 用户 / 客服"
          onExport={() => exportCsv<CsSession>("客服会话", [
            { header: "会话号", value: (s) => s.sessionNo },
            { header: "用户", value: (s) => s.userNo },
            { header: "客服", value: (s) => s.agentName },
            { header: "最新消息", value: (s) => s.lastMessage },
            { header: "状态", value: (s) => SESSION_STATUS[s.status].label },
            { header: "更新时间", value: (s) => s.updatedAt },
          ], (q.data?.list ?? []) as CsSession[])}
        />
      )}
      {tab === "tickets" && <DataTable rowKey={(t: CsTicket) => t.ticketNo} columns={ticketCols} rows={q.data?.list as CsTicket[]} loading={q.isLoading} empty="暂无报障工单——用户来电/APP 报障后在此登记，也可点右上「新增工单」手工建单" />}
      {tab === "sessions" && <DataTable rowKey={(s: CsSession) => s.sessionNo} columns={sessionCols} rows={q.data?.list as CsSession[]} loading={q.isLoading} empty="暂无客服会话——用户在 C 端发起在线咨询后会话才会出现在这里" />}
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

      {/* 会话详情：消息时间线 + 回复框。已结束的会话只读（后端也会拒） */}
      <Drawer
        open={!!session}
        onOpenChange={(o) => !o && setSession(null)}
        title={`会话 ${session?.sessionNo ?? ""}`}
        desc="用户与客服的完整对话；回复以当前登录客服身份发出"
        width="w-[520px]"
        footer={session && session.status === "ACTIVE" && canReply ? (
          <Button
            disabled={doReply.isPending || !reply.trim()}
            onClick={() => doReply.mutate({ no: session.sessionNo, content: reply.trim(), attach: attach.trim() || undefined })}
          >发送回复</Button>
        ) : session?.status === "CLOSED" ? (
          <span className="txt-body text-muted-foreground">会话已结束，不能再回复</span>
        ) : (
          // 权限降级句式由组件保证（§13），底部条里去掉 Notice 的下外边距
          <ReadOnlyNotice what="客服会话回复" perm="cs:session:reply" className="mb-0" />
        )}
      >
        {session && (
          <>
            <Field label="状态"><StatusBadge map={SESSION_STATUS} value={session.status} /></Field>
            <Field label="用户 / 客服">{session.userNo} · {session.agentName}</Field>
            <Field label="消息记录">
              {msgQ.isLoading
                ? <span className="text-muted-foreground">加载中…</span>
                : msgQ.data?.length
                  ? (
                    <ol className="space-y-2.5">
                      {msgQ.data.map((m) => (
                        <li key={m.id} className="border-l-2 border-[var(--border)] pl-3">
                          <div className="flex flex-wrap items-center gap-1.5">
                            <StatusBadge map={SENDER} value={m.senderType} />
                            <span className="text-xs text-muted-foreground tabular-nums">{m.senderNo} · {fmtTime(m.createdAt)}</span>
                          </div>
                          <div className="text-sm">{m.content}</div>
                          {m.attach && <a className="text-xs text-primary underline" href={m.attach} target="_blank" rel="noreferrer">查看附件</a>}
                        </li>
                      ))}
                    </ol>
                  )
                  : <span className="text-muted-foreground">暂无消息——用户发出第一条咨询后才会有记录</span>}
            </Field>
            {session.status === "ACTIVE" && canReply && (
              <>
                <Field label="回复内容">
                  {/* 走 Textarea 原语：手抄的类名串圆角/焦点环已与 Input 漂移 */}
                  <Textarea
                    rows={4} value={reply} onChange={setReply}
                    placeholder="直接回给用户的内容，会原样进入对话"
                  />
                </Field>
                <Field label="附件链接（可选）">
                  <Input value={attach} placeholder="截图 / 凭证的 URL" onChange={(e) => setAttach(e.target.value)} />
                </Field>
              </>
            )}
          </>
        )}
      </Drawer>
    </div>
  );
}

export default function CsPage() {
  return <Suspense fallback={null}><CsInner /></Suspense>;
}
