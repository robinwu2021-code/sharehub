"use client";

// 运营配置（原型 P6）：SLA 规则 · 工作日历 · 队列。改规则只影响新单；进行中的单按开单时的规则走完。
import { NeedPerm } from "@/components/flow/need-perm";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { failText, fmtSpan } from "@/lib/flow/present";
import type { QueueGroup, SlaPolicy, WorkCalendar } from "@/lib/flow/types";
import { notify } from "@/lib/notify";
import { PageTitle } from "@/components/ui/misc";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";

const WEEK = ["一", "二", "三", "四", "五", "六", "日"];
const PRIORITY_OPTS = [
  { value: "URGENT", label: "紧急" }, { value: "HIGH", label: "高" }, { value: "NORMAL", label: "普通" },
  { value: "LOW", label: "低" }, { value: "*", label: "*（其余）" },
];
const prioLabel = (c: string) => PRIORITY_OPTS.find((o) => o.value === c)?.label ?? c;
const SORT_LABEL: Record<QueueGroup["sort"], string> = { PRIORITY_THEN_AGE: "优先级 → 等待时长", AGE: "等待时长" };

type Form = Record<string, unknown>;

function FlowSettings() {
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: FLOW_KEY });
  const sla = useQuery({ queryKey: [...FLOW_KEY, "sla-policies"], queryFn: flow.slaPolicies });
  const cals = useQuery({ queryKey: [...FLOW_KEY, "calendars"], queryFn: flow.calendars });
  const queues = useQuery({ queryKey: [...FLOW_KEY, "queue-groups"], queryFn: flow.queueGroups });

  const [slaEdit, setSlaEdit] = useState<{ code: string | null; form: Form } | null>(null);
  const [calEdit, setCalEdit] = useState<{ code: string; form: Form } | null>(null);
  const [qEdit, setQEdit] = useState<{ isNew: boolean; form: Form } | null>(null);

  const calOpts = (cals.data ?? []).map((c) => ({ value: c.code, label: c.name }));
  const calName = (code?: string) => cals.data?.find((c) => c.code === code)?.name ?? code ?? "—";

  const saveSla = useMutation({
    mutationFn: () => {
      const f = slaEdit!.form;
      const type = flow.ticketTypeOptions().find((t) => t.code === f.ticketType)!;
      const p: SlaPolicy = {
        code: slaEdit!.code ?? "", ticketType: { code: type.code, name: type.name }, priority: String(f.priority),
        respondMin: Number(f.respondMin), resolveMin: Number(f.resolveMin), businessHours: !!f.businessHours,
        ...(f.businessHours ? { calendar: String(f.calendar ?? "") } : {}),
      };
      return flow.saveSlaPolicy(p);
    },
    onSuccess: () => { notify.success("SLA 规则已保存：只影响之后开的单"); setSlaEdit(null); refresh(); },
    onError: (e: Error) => notify.error(failText(e)),
  });
  const saveCal = useMutation({
    mutationFn: () => {
      const f = calEdit!.form;
      const c: WorkCalendar = {
        code: calEdit!.code, name: String(f.name ?? ""), timeZone: String(f.timeZone ?? ""),
        weekdays: String(f.weekdays ?? "").split(",").filter(Boolean).map(Number).sort(),
        open: String(f.open ?? ""), close: String(f.close ?? ""),
        holidays: String(f.holidays ?? "").split("\n").map((l) => l.trim()).filter(Boolean).map((l) => {
          const [date, ...rest] = l.split(/\s+/);
          return { date, name: rest.join(" ") || "节假日" };
        }),
      };
      return flow.saveCalendar(c);
    },
    onSuccess: () => { notify.success("工作日历已保存"); setCalEdit(null); refresh(); },
    onError: (e: Error) => notify.error(failText(e)),
  });
  const saveQ = useMutation({
    mutationFn: () => {
      const f = qEdit!.form;
      return flow.saveQueueGroup({ code: String(f.code ?? ""), name: String(f.name ?? ""), sort: f.sort as QueueGroup["sort"], claimTimeoutMin: Number(f.claimTimeoutMin) }, qEdit!.isNew);
    },
    onSuccess: () => { notify.success("队列已保存"); setQEdit(null); refresh(); },
    onError: (e: Error) => notify.error(failText(e)),
  });

  /**
   * @form-none SLA 规则提交到 OpsKit（PUT …/sla-policies/{code}，21/06 §四），不是 ShareHub 后端，contract.json 管不到。
   */
  const SLA_FIELDS: FieldDef[] = [
    { key: "ticketType", label: "适用工单类型", type: "select", required: true, options: flow.ticketTypeOptions().map((t) => ({ value: t.code, label: t.name })) },
    { key: "priority", label: "优先级", type: "select", required: true, options: PRIORITY_OPTS, help: "「*」= 同类型里没单独配的其余优先级" },
    { key: "respondMin", label: "响应时限（分钟）", type: "number", required: true, min: 1 },
    { key: "resolveMin", label: "解决时限（分钟）", type: "number", required: true, min: 1 },
    { key: "businessHours", label: "按营业时间计时", type: "switch", help: "关 = 7×24 小时连续计时" },
    { key: "calendar", label: "工作日历", type: "select", options: calOpts, disabledWhen: (v) => !v.businessHours },
  ];
  /**
   * @form-none 工作日历提交到 OpsKit（PUT /workspaces/{ws}/calendars/{code}，21/06 §三），不是 ShareHub 后端。
   */
  const CAL_FIELDS: FieldDef[] = [
    { key: "name", label: "名称", required: true, maxLength: 40 },
    { key: "timeZone", label: "时区", required: true, help: "IANA 时区，如 Asia/Dubai" },
    { key: "weekdays", label: "工作日", type: "multiselect", csv: true, required: true, options: WEEK.map((w, i) => ({ value: String(i + 1), label: `周${w}` })) },
    { key: "open", label: "上班时间", required: true, pattern: { re: "^([01]\\d|2[0-3]):[0-5]\\d$", msg: "HH:mm，如 10:00" } },
    { key: "close", label: "下班时间", required: true, pattern: { re: "^([01]\\d|2[0-3]):[0-5]\\d$", msg: "HH:mm，如 22:00" } },
    { key: "holidays", label: "节假日", type: "textarea", rows: 4, help: "一行一个：日期 名称，如「2026-12-02 国庆」" },
  ];
  /**
   * @form-none 队列提交到 OpsKit（PUT …/queue-groups/{code}，21/06 §四），不是 ShareHub 后端。
   */
  const Q_FIELDS: FieldDef[] = [
    { key: "code", label: "代号", required: true, readOnlyOnEdit: true, help: "小写字母、数字、连字符，创建后不能改" },
    { key: "name", label: "名称", required: true, maxLength: 40 },
    { key: "sort", label: "排序", type: "select", required: true, options: Object.entries(SORT_LABEL).map(([value, label]) => ({ value, label })) },
    { key: "claimTimeoutMin", label: "领取超时（分钟）", type: "number", required: true, min: 5, max: 240, help: "领取后超时未决定，自动放回队列并留痕" },
  ];

  const slaCols: Column<SlaPolicy>[] = [
    { header: "适用", cell: (p) => p.ticketType.name },
    { header: "优先级", cell: (p) => prioLabel(p.priority) },
    { header: "响应", cell: (p) => fmtSpan(p.respondMin) },
    { header: "解决", cell: (p) => fmtSpan(p.resolveMin) },
    { header: "营业时间", cell: (p) => (p.businessHours ? "是" : "否（7×24）") },
    { header: "日历", cell: (p) => (p.businessHours ? calName(p.calendar) : "—") },
    {
      header: "操作", className: "text-end",
      cell: (p) => <Button size="sm" variant="secondary" onClick={() => setSlaEdit({ code: p.code, form: {
        ticketType: p.ticketType.code, priority: p.priority, respondMin: p.respondMin, resolveMin: p.resolveMin, businessHours: p.businessHours, calendar: p.calendar ?? "",
      } })}>编辑</Button>,
    },
  ];
  const qCols: Column<QueueGroup>[] = [
    { header: "队列", cell: (q) => <span className="txt-strong">{q.name}</span> },
    { header: "代号", pk: true, cell: (q) => q.code },
    { header: "排序", cell: (q) => SORT_LABEL[q.sort] },
    { header: "领取超时", cell: (q) => fmtSpan(q.claimTimeoutMin) },
    { header: "操作", className: "text-end", cell: (q) => <Button size="sm" variant="secondary" onClick={() => setQEdit({ isNew: false, form: { ...q } })}>编辑</Button> },
  ];

  return (
    <div>
      <PageTitle title="运营配置" desc="SLA 规则 · 工作日历 · 队列。" />
      <Notice>改规则只影响新单；进行中的单按开单时的规则走完。</Notice>

      <Card className="mb-4 p-5">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="txt-strong">SLA 规则</h2>
          <Button size="sm" onClick={() => setSlaEdit({ code: null, form: { ticketType: "ops", priority: "*", respondMin: 60, resolveMin: 480, businessHours: false, calendar: "" } })}>
            <Plus className="size-4" /> 新增规则
          </Button>
        </div>
        <DataTable columns={slaCols} rows={sla.data} loading={sla.isLoading} error={sla.error} onRetry={() => sla.refetch()}
          rowKey={(p) => p.code} empty="还没有 SLA 规则 —— 没有规则的工单类型不计时" />
      </Card>

      <Card className="mb-4 p-5">
        <h2 className="mb-3 txt-strong">工作日历</h2>
        {cals.error ? <p className="txt-body text-destructive-ink">{(cals.error as Error).message}</p> : (cals.data ?? []).map((c) => (
          <div key={c.code} className="flex flex-wrap items-center justify-between gap-3 border-b border-[var(--border)] py-2 last:border-0">
            <div>
              <div className="txt-strong">{c.name}</div>
              <div className="txt-caption text-muted-foreground">
                时区 {c.timeZone} · 周{c.weekdays.map((d) => WEEK[d - 1]).join("、")} {c.open}–{c.close}
                {c.holidays.length ? ` · 节假日 ${c.holidays.length} 天（${c.holidays.slice(0, 2).map((h) => `${h.date.slice(5)} ${h.name}`).join("、")}${c.holidays.length > 2 ? "…" : ""}）` : ""}
              </div>
            </div>
            <Button size="sm" variant="secondary" onClick={() => setCalEdit({ code: c.code, form: {
              name: c.name, timeZone: c.timeZone, weekdays: c.weekdays.join(","), open: c.open, close: c.close,
              holidays: c.holidays.map((h) => `${h.date} ${h.name}`).join("\n"),
            } })}>编辑</Button>
          </div>
        ))}
      </Card>

      <Card className="p-5">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="txt-strong">队列</h2>
          <Button size="sm" onClick={() => setQEdit({ isNew: true, form: { code: "", name: "", sort: "PRIORITY_THEN_AGE", claimTimeoutMin: 30 } })}>
            <Plus className="size-4" /> 新增
          </Button>
        </div>
        <DataTable columns={qCols} rows={queues.data} loading={queues.isLoading} error={queues.error} onRetry={() => queues.refetch()}
          rowKey={(q) => q.code} empty="还没有队列 —— 领取制审核要先建队列" />
      </Card>

      <FormDrawer open={!!slaEdit} onOpenChange={(o) => { if (!o) setSlaEdit(null); }} titleNew="新增 SLA 规则" titleEdit="编辑 SLA 规则"
        isEdit={!!slaEdit?.code} fields={SLA_FIELDS} value={slaEdit?.form ?? {}} onChange={(f) => setSlaEdit((s) => (s ? { ...s, form: f } : s))}
        onSubmit={() => saveSla.mutate()} submitting={saveSla.isPending} />
      <FormDrawer open={!!calEdit} onOpenChange={(o) => { if (!o) setCalEdit(null); }} titleNew="编辑工作日历" titleEdit="编辑工作日历"
        isEdit fields={CAL_FIELDS} value={calEdit?.form ?? {}} onChange={(f) => setCalEdit((s) => (s ? { ...s, form: f } : s))}
        onSubmit={() => saveCal.mutate()} submitting={saveCal.isPending} />
      <FormDrawer open={!!qEdit} onOpenChange={(o) => { if (!o) setQEdit(null); }} titleNew="新增队列" titleEdit="编辑队列"
        isEdit={!qEdit?.isNew} fields={Q_FIELDS} value={qEdit?.form ?? {}} onChange={(f) => setQEdit((s) => (s ? { ...s, form: f } : s))}
        onSubmit={() => saveQ.mutate()} submitting={saveQ.isPending} />
    </div>
  );
}

export default function FlowSettingsPage() {
  return <NeedPerm code="flow:config:manage" what="运营配置"><FlowSettings /></NeedPerm>;
}
