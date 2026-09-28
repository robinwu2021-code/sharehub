"use client";

// SLA 策略（运营配置页的一节）：列表 + 内联编辑器，形态与流程配置页一致。
// 模型是后端的通用策略（21/02 §6.5 · O2-8）：指标 × 目标表（自上而下第一条匹配的行生效，最后一行兜底）。
// 界面只编辑常用那部分：两个标准指标（首次响应 / 解决）、按优先级分行、每行时限与日历、预警比例；
// API 登记的高级条件（all / any、别的字段）与自定义指标**只读展示、原样保留**，界面不改写它们。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { useFlowT } from "@/lib/flow/i18n";
import { useTicketTypes } from "@/lib/flow/options";
import { isValidationError } from "@/lib/flow/errors";
import type { FieldError, I18nText, SlaCondition, SlaPolicy, SlaTarget } from "@/lib/flow/types";
import { notify } from "@/lib/notify";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { Notice } from "@/components/ui/notice";
import { DataTable, type Column } from "@/components/ui/data-table";

const STD = ["FIRST_RESPONSE", "RESOLUTION"] as const;
const FALLBACK = "*";
const ADVANCED = "__advanced";

/** 行条件在界面上的选项：优先级 = X · 兜底 · 高级（原样保留） */
function whenKey(w?: SlaCondition): string {
  if (!w) return FALLBACK;
  if ("field" in w && w.field === "priority" && w.op === "EQ" && typeof w.value === "string") return w.value;
  return ADVANCED;
}
const pick = (t: I18nText, locale: keyof I18nText) => t[locale] || t.en || t.zh || t.ar || "";

export function SlaPolicies({ calendarsDemo }: { calendarsDemo?: boolean }) {
  const qc = useQueryClient();
  const { t, tc, locale } = useFlowT("sla");
  // 行条件里的优先级取自工单类型的优先级选项集（代号与名称都由租户定，如 P1 紧急），前端不内置
  const ticketTypes = useTicketTypes();
  const priorities = [...new Map(ticketTypes.flatMap((ty) => ty.priorityOptions.map((o) => [o.code, o.name] as const))).entries()];
  const list = useQuery({ queryKey: [...FLOW_KEY, "sla-policies"], queryFn: flow.slaPolicies });
  const cals = useQuery({ queryKey: [...FLOW_KEY, "calendars"], queryFn: flow.calendars });
  const [editing, setEditing] = useState<{ p: SlaPolicy; isNew: boolean } | null>(null);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const err = (path: string) => errors.find((e) => e.path === path)?.error;
  const errUnder = (prefix: string) => errors.find((e) => e.path.startsWith(prefix))?.error;

  const metricName = (p: SlaPolicy, code: string) => {
    const m = p.metrics.find((x) => x.code === code);
    return (STD as readonly string[]).includes(code) ? t(`metric.${code as (typeof STD)[number]}`) : m ? pick(m.name, locale) || code : code;
  };

  const save = useMutation({
    mutationFn: () => flow.saveSlaPolicy(editing!.p, editing!.isNew),
    onSuccess: (r) => {
      setErrors([]);
      if (r.created) notify.success(t("saved", { version: r.version })); else notify.info(t("unchanged"));
      setEditing((e) => (e ? { p: { ...e.p, note: undefined, version: r.version }, isNew: false } : e));
      qc.invalidateQueries({ queryKey: FLOW_KEY });
    },
    onError: (e: Error) => { if (isValidationError(e)) setErrors(e.errors); notify.error(e.message); },
  });

  const set = (fn: (p: SlaPolicy) => void) => setEditing((cur) => {
    if (!cur) return cur;
    const p = structuredClone(cur.p); fn(p); return { ...cur, p };
  });
  const setRow = (i: number, fn: (r: SlaTarget) => void) => set((p) => fn(p.targets[i]));
  const move = (i: number, d: -1 | 1) => set((p) => { const [r] = p.targets.splice(i, 1); p.targets.splice(i + d, 0, r); });

  const toggleMetric = (code: (typeof STD)[number], on: boolean) => set((p) => {
    if (on && !p.metrics.some((m) => m.code === code)) {
      p.metrics.push({ code, name: { zh: t(`metric.${code}`) } });
    } else if (!on) {
      p.metrics = p.metrics.filter((m) => m.code !== code);
      p.targets.forEach((r) => { delete r.targetsMin[code]; });
    }
  });

  const blank = (): SlaPolicy => ({
    code: "", name: {}, warnAtPercent: 80,
    metrics: STD.map((c) => ({ code: c, name: { zh: t(`metric.${c}`) } })),
    targets: [{ targetsMin: { FIRST_RESPONSE: 60, RESOLUTION: 480 } }],
  });

  const columns: Column<SlaPolicy>[] = [
    { header: t("col.name"), cell: (p) => <span className="txt-strong">{pick(p.name, locale) || p.code}</span> },
    { header: t("col.code"), pk: true, cell: (p) => p.code },
    { header: t("col.metrics"), cell: (p) => p.metrics.map((m) => metricName(p, m.code)).join(tc("sep")) },
    { header: t("col.rows"), cell: (p) => t("rows", { n: p.targets.length }) },
    { header: t("col.warn"), cell: (p) => (p.warnAtPercent ? `${p.warnAtPercent}%` : tc("none")) },
    { header: t("col.version"), cell: (p) => <span className="font-mono text-xs">{p.version ?? tc("none")}</span> },
    {
      header: t("col.actions"), className: "text-end",
      cell: (p) => <Button size="sm" variant="secondary" onClick={() => { setErrors([]); setEditing({ p: structuredClone(p), isNew: false }); }}>{t("edit")}</Button>,
    },
  ];

  const p = editing?.p;
  const custom = p?.metrics.filter((m) => !(STD as readonly string[]).includes(m.code)) ?? [];
  const timed = p?.metrics.map((m) => m.code) ?? [];

  return (
    <Card className="mb-4 p-5">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="txt-strong">{t("title")}</h2>
        <Button size="sm" onClick={() => { setErrors([]); setEditing({ p: blank(), isNew: true }); }}><Plus className="size-4" /> {t("add")}</Button>
      </div>
      <p className="mb-3 txt-caption text-muted-foreground">{t("hint")}</p>
      <DataTable columns={columns} rows={list.data} loading={list.isLoading} error={list.error} onRetry={() => list.refetch()}
        rowKey={(x) => x.code} empty={t("empty")} />

      {p && editing && (
        <div className="mt-5 rounded-card border border-[var(--border)] p-5" data-slot="sla-editor">
          <h3 className="mb-4 txt-strong">{editing.isNew ? t("editor.new") : t("editor.edit", { name: pick(p.name, locale) || p.code })}</h3>
          {errors.length > 0 && (
            <Notice className="mb-3 border-[var(--destructive)] text-destructive-ink">
              {errors.map((e) => `${e.path} ${e.error}`).join(tc("listSep"))}
            </Notice>
          )}
          <div className="mb-4 grid gap-3 sm:grid-cols-3">
            {editing.isNew && (
              <Field label={t("field.code")} help={t("field.codeHelp")} error={err("code")}>
                <Input value={p.code} aria-invalid={!!err("code")} onChange={(e) => set((x) => { x.code = e.target.value.trim(); })} />
              </Field>
            )}
            <Field label={t("field.name")} help={t("field.nameHelp")} error={err("name")}>
              <Input value={p.name[locale] ?? ""} placeholder={pick(p.name, locale)} aria-invalid={!!err("name")}
                onChange={(e) => set((x) => { x.name[locale] = e.target.value; })} />
            </Field>
            <Field label={t("field.warn")} help={t("field.warnHelp")} error={err("warnAtPercent")}>
              <Input type="number" min={1} max={99} value={p.warnAtPercent ?? ""} aria-invalid={!!err("warnAtPercent")}
                onChange={(e) => set((x) => { x.warnAtPercent = e.target.value === "" ? undefined : Number(e.target.value); })} />
            </Field>
          </div>

          <div className="mb-4">
            <span className="mb-1 block txt-label text-muted-foreground">{t("field.metrics")}</span>
            <div className="flex flex-wrap items-center gap-4">
              {STD.map((c) => (
                <label key={c} className="inline-flex items-center gap-2 txt-body">
                  <Checkbox checked={timed.includes(c)} onChange={(v) => toggleMetric(c, v === true)} />
                  {t(`metric.${c}`)}
                </label>
              ))}
              {custom.map((m) => <span key={m.code} className="txt-caption text-muted-foreground">{t("metric.custom", { name: pick(m.name, locale) || m.code })}</span>)}
            </div>
            {err("metrics") && <span className="mt-1 block txt-caption text-destructive-ink">{err("metrics")}</span>}
          </div>

          <span className="mb-1 block txt-label text-muted-foreground">{t("target.title")}</span>
          {timed.length === 0 ? <p className="txt-caption text-muted-foreground">{t("target.noMetric")}</p> : (
            <div className="overflow-x-auto rounded-card border border-[var(--border)]">
              <table className="w-full txt-body">
                <thead>
                  <tr className="text-start txt-label text-muted-foreground">
                    <th className="px-3 py-2 text-start">{t("target.when")}</th>
                    {timed.map((c) => <th key={c} className="px-3 py-2 text-start">{t("target.metricCol", { name: metricName(p, c) })}</th>)}
                    <th className="px-3 py-2 text-start">{t("target.calendar")}</th>
                    <th className="px-3 py-2" />
                  </tr>
                </thead>
                <tbody>
                  {p.targets.map((r, i) => {
                    const k = whenKey(r.when);
                    const rowErr = errUnder(`targets[${i}]`);
                    return (
                      <tr key={i} className="border-t border-[var(--border)] align-top">
                        <td className="px-3 py-2">
                          {k === ADVANCED ? <span className="txt-caption text-muted-foreground">{t("target.advanced")}</span> : (
                            <Select className="w-44" value={k} aria-label={t("target.when")}
                              onChange={(e) => setRow(i, (x) => { const v = e.target.value; x.when = v === FALLBACK ? undefined : { field: "priority", op: "EQ", value: v }; })}>
                              {/* 已存的代号不在任何类型的选项里（类型改过）时也列出来，原样显示代号，不让它悄悄变成别的 */}
                              {(priorities.some(([c]) => c === k) || k === FALLBACK ? priorities : [...priorities, [k, k] as const])
                                .map(([code, name]) => <option key={code} value={code}>{t("target.priority", { p: name })}</option>)}
                              <option value={FALLBACK}>{t("target.fallback")}</option>
                            </Select>
                          )}
                          {rowErr && <span className="mt-1 block txt-caption text-destructive-ink">{rowErr}</span>}
                        </td>
                        {timed.map((c) => (
                          <td key={c} className="px-3 py-2">
                            <Input type="number" min={1} className="w-28" value={r.targetsMin[c] ?? ""} aria-label={metricName(p, c)}
                              aria-invalid={!!err(`targets[${i}].targetsMin.${c}`)}
                              onChange={(e) => setRow(i, (x) => { if (e.target.value === "") delete x.targetsMin[c]; else x.targetsMin[c] = Number(e.target.value); })} />
                          </td>
                        ))}
                        <td className="px-3 py-2">
                          <Select className="w-44" value={r.calendar ?? ""} aria-label={t("target.calendar")}
                            onChange={(e) => setRow(i, (x) => { x.calendar = e.target.value || undefined; })}>
                            <option value="">{t("target.allDay")}</option>
                            {(cals.data ?? []).map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}
                          </Select>
                        </td>
                        <td className="px-3 py-2 text-end whitespace-nowrap">
                          <Button size="icon" variant="ghost" aria-label={t("target.up")} disabled={i === 0} onClick={() => move(i, -1)}><ArrowUp /></Button>
                          <Button size="icon" variant="ghost" aria-label={t("target.down")} disabled={i === p.targets.length - 1} onClick={() => move(i, 1)}><ArrowDown /></Button>
                          <Button size="icon" variant="ghost" aria-label={t("target.remove")} disabled={p.targets.length === 1}
                            onClick={() => set((x) => { x.targets.splice(i, 1); })}><Trash2 /></Button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
          {calendarsDemo && <p className="mt-2 txt-caption text-muted-foreground">{t("calendarPending")}</p>}
          <Button className="mt-3" size="sm" variant="secondary" disabled={timed.length === 0 || priorities.length === 0}
            onClick={() => set((x) => {
              // 新行插在兜底行之前（兜底必须最后）；没有兜底行时追加
              const at = x.targets.length && !x.targets[x.targets.length - 1].when ? x.targets.length - 1 : x.targets.length;
              x.targets.splice(at, 0, { when: { field: "priority", op: "EQ", value: priorities[0]?.[0] ?? "" }, targetsMin: {} });
            })}>
            <Plus className="size-4" /> {t("target.add")}
          </Button>

          <div className="mt-5 flex flex-wrap items-end gap-3">
            <Field label={t("field.note")} className="min-w-60 flex-1">
              <Input value={p.note ?? ""} placeholder={t("field.notePlaceholder")} onChange={(e) => set((x) => { x.note = e.target.value || undefined; })} />
            </Field>
            <Button variant="secondary" onClick={() => { setEditing(null); setErrors([]); }}>{t("close")}</Button>
            <Button loading={save.isPending} onClick={() => save.mutate()}>{t("save")}</Button>
          </div>
        </div>
      )}
    </Card>
  );
}

function Field({ label, error, help, className, children }: { label: string; error?: string; help?: string; className?: string; children: React.ReactNode }) {
  return (
    <label className={["block", className].filter(Boolean).join(" ")}>
      <span className="mb-1 block txt-label text-muted-foreground">{label}</span>
      {children}
      {error ? <span className="mt-1 block txt-caption text-destructive-ink">{error}</span>
        : help ? <span className="mt-1 block txt-caption text-muted-foreground">{help}</span> : null}
    </label>
  );
}
