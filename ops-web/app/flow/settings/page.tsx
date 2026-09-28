"use client";

// 运营配置（原型 P6）：SLA 规则 · 工作日历 · 队列。改规则只影响新单；进行中的单按开单时的规则走完。
import { NeedPerm } from "@/components/flow/need-perm";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { useFlowT } from "@/lib/flow/i18n";
import type { QueueGroup, WorkCalendar } from "@/lib/flow/types";
import { FLOW_USE_HTTP } from "@/lib/flow/http";
import { SlaPolicies } from "@/components/flow/sla-policies";
import { TicketTypes } from "@/components/flow/ticket-types";
import { notify } from "@/lib/notify";
import { PageTitle } from "@/components/ui/misc";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";

const WEEK_KEYS = ["d1", "d2", "d3", "d4", "d5", "d6", "d7"] as const;
const ORDER_CODES: QueueGroup["orderBy"][] = ["PRIORITY", "DUE", "CREATED"];

type Form = Record<string, unknown>;

function FlowSettings() {
  const qc = useQueryClient();
  const { t: tt, tc, fmt } = useFlowT("settings");
  const WEEK = WEEK_KEYS.map((k) => tt(`week.${k}`));
  const ORDER_LABEL = Object.fromEntries(ORDER_CODES.map((k) => [k, tt(`order.${k}`)])) as Record<QueueGroup["orderBy"], string>;
  const refresh = () => qc.invalidateQueries({ queryKey: FLOW_KEY });
  const cals = useQuery({ queryKey: [...FLOW_KEY, "calendars"], queryFn: flow.calendars });
  const queues = useQuery({ queryKey: [...FLOW_KEY, "queue-groups"], queryFn: flow.queueGroups });

  const [calEdit, setCalEdit] = useState<{ code: string; form: Form } | null>(null);
  const [qEdit, setQEdit] = useState<{ isNew: boolean; form: Form } | null>(null);


  const saveCal = useMutation({
    mutationFn: () => {
      const f = calEdit!.form;
      const c: WorkCalendar = {
        code: calEdit!.code, name: String(f.name ?? ""), timeZone: String(f.timeZone ?? ""),
        weekdays: String(f.weekdays ?? "").split(",").filter(Boolean).map(Number).sort(),
        open: String(f.open ?? ""), close: String(f.close ?? ""),
        holidays: String(f.holidays ?? "").split("\n").map((l) => l.trim()).filter(Boolean).map((l) => {
          const [date, ...rest] = l.split(/\s+/);
          return { date, name: rest.join(" ") || tt("cal.holidayDefault") };
        }),
      };
      return flow.saveCalendar(c);
    },
    onSuccess: () => { notify.success(tt("saved.cal")); setCalEdit(null); refresh(); },
    onError: (e: Error) => notify.error(fmt.fail(e)),
  });
  const saveQ = useMutation({
    mutationFn: () => {
      const f = qEdit!.form;
      // 界面按分钟填，契约是秒（claimTimeoutSec）
      return flow.saveQueueGroup({
        code: String(f.code ?? ""), name: String(f.name ?? ""), kind: "QUEUE",
        orderBy: f.orderBy as QueueGroup["orderBy"], claimTimeoutSec: Number(f.claimTimeoutMin) * 60,
      }, qEdit!.isNew);
    },
    onSuccess: () => { notify.success(tt("saved.queue")); setQEdit(null); refresh(); },
    onError: (e: Error) => notify.error(fmt.fail(e)),
  });

  /**
   * @form-none 工作日历提交到 OpsKit（PUT /workspaces/{ws}/calendars/{code}，21/06 §三），不是 ShareHub 后端。
   */
  const CAL_FIELDS: FieldDef[] = [
    { key: "name", label: tt("calField.name"), required: true, maxLength: 40 },
    { key: "timeZone", label: tt("calField.timeZone"), required: true, help: tt("calField.timeZoneHelp") },
    { key: "weekdays", label: tt("calField.weekdays"), type: "multiselect", csv: true, required: true, options: WEEK.map((w, i) => ({ value: String(i + 1), label: tt("weekday", { d: w }) })) },
    { key: "open", label: tt("calField.open"), required: true, pattern: { re: "^([01]\\d|2[0-3]):[0-5]\\d$", msg: tt("calField.openMsg") } },
    { key: "close", label: tt("calField.close"), required: true, pattern: { re: "^([01]\\d|2[0-3]):[0-5]\\d$", msg: tt("calField.closeMsg") } },
    { key: "holidays", label: tt("calField.holidays"), type: "textarea", rows: 4, help: tt("calField.holidaysHelp") },
  ];
  /**
   * @form-none 队列提交到 OpsKit（队列即 kind=QUEUE 的组：PUT /workspaces/{ws}/groups/{code}，21/06 §三），不是 ShareHub 后端。
   */
  const Q_FIELDS: FieldDef[] = [
    { key: "code", label: tt("qField.code"), required: true, readOnlyOnEdit: true, help: tt("qField.codeHelp") },
    { key: "name", label: tt("qField.name"), required: true, maxLength: 40 },
    { key: "orderBy", label: tt("qField.orderBy"), type: "select", required: true, options: Object.entries(ORDER_LABEL).map(([value, label]) => ({ value, label })) },
    { key: "claimTimeoutMin", label: tt("qField.claimTimeoutMin"), type: "number", required: true, min: 5, max: 240, help: tt("qField.claimTimeoutHelp") },
  ];

  const qCols: Column<QueueGroup>[] = [
    { header: tt("qCol.name"), cell: (q) => <span className="txt-strong">{q.name}</span> },
    { header: tt("qCol.code"), pk: true, cell: (q) => q.code },
    { header: tt("qCol.orderBy"), cell: (q) => ORDER_LABEL[q.orderBy] },
    { header: tt("qCol.claimTimeout"), cell: (q) => fmt.span(q.claimTimeoutSec / 60) },
    { header: tt("qCol.actions"), className: "text-end", cell: (q) => <Button size="sm" variant="secondary"
      onClick={() => setQEdit({ isNew: false, form: { code: q.code, name: q.name, orderBy: q.orderBy, claimTimeoutMin: q.claimTimeoutSec / 60 } })}>{tt("btn.edit")}</Button> },
  ];

  return (
    <div>
      <PageTitle title={tc("page.settings")} desc={tt("desc")} />
      <Notice>{tt("notice")}</Notice>

      <TicketTypes />
      <SlaPolicies calendarsDemo={FLOW_USE_HTTP} />


      <Card className="mb-4 p-5">
        <h2 className="mb-3 txt-strong">{tt("section.cal")}</h2>
        {cals.error ? <p className="txt-body text-destructive-ink">{(cals.error as Error).message}</p> : (cals.data ?? []).map((c) => (
          <div key={c.code} className="flex flex-wrap items-center justify-between gap-3 border-b border-[var(--border)] py-2 last:border-0">
            <div>
              <div className="txt-strong">{c.name}</div>
              <div className="txt-caption text-muted-foreground">
                {tt("cal.summary", { tz: c.timeZone, days: tt("weekdays", { days: c.weekdays.map((d) => WEEK[d - 1]).join(tt("enumSep")) }), open: c.open, close: c.close })}
                {c.holidays.length ? tt("cal.holidays", {
                  n: c.holidays.length,
                  list: c.holidays.slice(0, 2).map((h) => `${h.date.slice(5)} ${h.name}`).join(tt("enumSep")),
                  more: c.holidays.length > 2 ? tt("cal.more") : "",
                }) : ""}
              </div>
            </div>
            <Button size="sm" variant="secondary" onClick={() => setCalEdit({ code: c.code, form: {
              name: c.name, timeZone: c.timeZone, weekdays: c.weekdays.join(","), open: c.open, close: c.close,
              holidays: c.holidays.map((h) => `${h.date} ${h.name}`).join("\n"),
            } })}>{tt("btn.edit")}</Button>
          </div>
        ))}
      </Card>

      <Card className="p-5">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="txt-strong">{tt("section.queue")}</h2>
          <Button size="sm" onClick={() => setQEdit({ isNew: true, form: { code: "", name: "", orderBy: "PRIORITY", claimTimeoutMin: 30 } })}>
            <Plus className="size-4" /> {tt("btn.add")}
          </Button>
        </div>
        <DataTable columns={qCols} rows={queues.data} loading={queues.isLoading} error={queues.error} onRetry={() => queues.refetch()}
          rowKey={(q) => q.code} empty={tt("empty.queue")} />
      </Card>

      <FormDrawer open={!!calEdit} onOpenChange={(o) => { if (!o) setCalEdit(null); }} titleNew={tt("drawer.calEdit")} titleEdit={tt("drawer.calEdit")}
        isEdit fields={CAL_FIELDS} value={calEdit?.form ?? {}} onChange={(f) => setCalEdit((s) => (s ? { ...s, form: f } : s))}
        onSubmit={() => saveCal.mutate()} submitting={saveCal.isPending} />
      <FormDrawer open={!!qEdit} onOpenChange={(o) => { if (!o) setQEdit(null); }} titleNew={tt("drawer.qNew")} titleEdit={tt("drawer.qEdit")}
        isEdit={!qEdit?.isNew} fields={Q_FIELDS} value={qEdit?.form ?? {}} onChange={(f) => setQEdit((s) => (s ? { ...s, form: f } : s))}
        onSubmit={() => saveQ.mutate()} submitting={saveQ.isPending} />
    </div>
  );
}

export default function FlowSettingsPage() {
  return <NeedPerm code="flow:config:manage" page="settings"><FlowSettings /></NeedPerm>;
}
