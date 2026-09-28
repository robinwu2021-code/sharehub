"use client";

// 流程配置（原型 P5a）：表单化配置审批流程（不做图形设计器）。编辑的是「审批模板」（21/02 §6.4a）——
// 线性步骤，服务端编译成审批类流程。保存即发布新版本：版本 = 内容哈希，只增不改；
// 进行中的审批按提交时的版本走完，新版本只影响新提交。
// 校验在服务端（422 逐条带路径，路径按模板字段给），这里把每条错误挂到对应字段、并在顶部汇总。
// 名称是多语种（name{i18n}）：按当前界面语言编辑那一种，其他语种原样保留。
import { NeedPerm } from "@/components/flow/need-perm";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { isValidationError } from "@/lib/flow/errors";
import { ALL_MODE_KINDS } from "@/lib/flow/types";
import type { ApprovalTemplate, ApproverKind, ApproverRef, ConditionOp, FieldError, I18nText, TemplateStep, TemplateSummary } from "@/lib/flow/types";
import { useFlowT } from "@/lib/flow/i18n";
import { notify } from "@/lib/notify";
import { PageTitle } from "@/components/ui/misc";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Notice } from "@/components/ui/notice";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { FlowTimeline } from "@/components/flow/flow-timeline";
import { TemplateFormFields } from "@/components/flow/template-form-fields";

type PT = ReturnType<typeof useFlowT<"processes">>["t"];
const enabledMap = (tt: PT): StatusMap<"on" | "off"> => ({ on: { label: tt("enabled.on"), tone: "success" }, off: { label: tt("enabled.off"), tone: "muted" } });
const APPROVER_KINDS: ApproverKind[] = ["PERM", "ROLE", "GROUP", "MEMBER", "FIELD"];
const allowsAll = (k: ApproverKind) => ALL_MODE_KINDS.includes(k);
/** 换审批人种类时保留已填的内容：成员是列表，其余是单个代号 */
const withKind = (a: ApproverRef, kind: ApproverKind): ApproverRef => {
  const text = a.kind === "MEMBER" ? a.value.join(",") : a.value;
  return kind === "MEMBER" ? { kind, value: text ? text.split(",") : [] } : { kind, value: text };
};
/** 编辑中的原文：成员列表按逗号拼回，不在输入时去空项（否则打不出逗号） */
const approverText = (a: ApproverRef) => (a.kind === "MEMBER" ? a.value.join(",") : a.value);
const setApproverText = (a: ApproverRef, text: string): ApproverRef =>
  a.kind === "MEMBER" ? { kind: a.kind, value: text ? text.split(",") : [] } : { kind: a.kind, value: text };
/** 当前语言没填时的占位提示：与服务端显示回退一致（en → 任意一种） */
const fallback = (t: I18nText) => t.en || t.zh || t.ar || "";
const condOptions = (tt: PT): { value: "" | ConditionOp; label: string }[] => [
  { value: "", label: tt("cond.none") }, { value: "TRUE", label: tt("cond.TRUE") }, { value: "EQ", label: tt("cond.EQ") }, { value: "IN", label: tt("cond.IN") },
];

const blankStep = (): TemplateStep => ({ code: "", name: {}, approver: { kind: "PERM", value: "" }, mode: "ANY", dueHours: 24 });

function FlowProcesses() {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const { t: tt, tc, fmt, locale } = useFlowT("processes");
  const ENABLED = enabledMap(tt);
  const COND = condOptions(tt);
  const list = useQuery({ queryKey: [...FLOW_KEY, "approval-templates"], queryFn: flow.templates });
  const [editing, setEditing] = useState<{ def: ApprovalTemplate; isNew: boolean } | null>(null);
  const [note, setNote] = useState("");
  const [errors, setErrors] = useState<FieldError[]>([]);
  const code = editing && !editing.isNew ? editing.def.code : null;
  const versions = useQuery({
    queryKey: [...FLOW_KEY, "template-versions", code], queryFn: () => flow.templateVersions(code!), enabled: !!code,
  });

  const open = async (p: TemplateSummary) => {
    setErrors([]); setNote("");
    setEditing({ def: await flow.template(p.code), isNew: false });
  };
  const toggle = useMutation({
    mutationFn: (p: TemplateSummary) => flow.setTemplateEnabled(p.code, !p.enabled),
    onSuccess: (_v, p) => { notify.success(p.enabled ? tt("toast.disabled", { name: p.name }) : tt("toast.enabled", { name: p.name })); qc.invalidateQueries({ queryKey: FLOW_KEY }); },
    onError: (e: Error) => notify.error(e.message),
  });
  const publish = useMutation({
    mutationFn: () => flow.publishTemplate(editing!.def, note, editing!.isNew),
    onSuccess: (r) => {
      setErrors([]);
      if (!r.created) notify.info(tt("toast.unchanged"));
      else notify.success(r.runningOnOlderVersions ? tt("toast.publishedRunning", { version: r.version, n: r.runningOnOlderVersions }) : tt("toast.published", { version: r.version }));
      setNote("");
      if (editing?.isNew) setEditing({ def: editing.def, isNew: false });
      qc.invalidateQueries({ queryKey: FLOW_KEY });
    },
    onError: (e: Error) => {
      if (isValidationError(e)) setErrors(e.errors);
      notify.error(e.message);
    },
  });

  const err = (path: string) => errors.find((x) => x.path === path)?.error;
  const set = (fn: (d: ApprovalTemplate) => void) => setEditing((cur) => {
    if (!cur) return cur;
    const def = structuredClone(cur.def);
    fn(def);
    return { ...cur, def };
  });
  const setStep = (i: number, fn: (s: TemplateStep) => void) => set((d) => fn(d.steps[i]));
  const move = (i: number, dir: -1 | 1) => set((d) => { const [s] = d.steps.splice(i, 1); d.steps.splice(i + dir, 0, s); });

  const columns: Column<TemplateSummary>[] = [
    { header: tt("col.name"), cell: (p) => <span className="txt-strong">{p.name}</span> },
    { header: tt("col.code"), pk: true, cell: (p) => p.code },
    { header: tt("col.steps"), cell: (p) => p.stepsSummary },
    { header: tt("col.version"), cell: (p) => <span className="font-mono text-xs">{p.version}</span> },
    { header: tt("col.running"), className: "text-end", cell: (p) => <span className="tabular-nums">{p.running}</span> },
    { header: tt("col.status"), cell: (p) => <StatusBadge map={ENABLED} value={p.enabled ? "on" : "off"} /> },
    {
      header: tt("col.actions"), className: "text-end",
      cell: (p) => (
        <span className="inline-flex gap-2">
          <Button size="sm" variant="secondary" onClick={() => open(p)}>{tt("btn.edit")}</Button>
          <Button size="sm" variant={p.enabled ? "destructive" : "secondary"} disabled={toggle.isPending}
            onClick={async () => {
              if (!p.enabled || await confirm({ title: tt("confirmDisable.title", { name: p.name }), desc: tt("confirmDisable.desc", { n: p.running }), confirmText: tt("btn.disable"), danger: true })) toggle.mutate(p);
            }}>
            {p.enabled ? tt("btn.disable") : tt("btn.enable")}
          </Button>
        </span>
      ),
    },
  ];

  return (
    <div>
      <PageTitle title={tc("page.processes")} desc={tt("desc")} />

      <Card className="mb-4 p-5">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="txt-strong">{tt("list.title")}</h2>
          <Button size="sm" onClick={() => { setErrors([]); setNote(""); setEditing({ isNew: true, def: { code: "", name: {}, rules: { selfApproval: false, crossStepExclusive: true }, steps: [blankStep()] } }); }}>
            <Plus className="size-4" /> {tt("btn.create")}
          </Button>
        </div>
        <DataTable columns={columns} rows={list.data} loading={list.isLoading} error={list.error} onRetry={() => list.refetch()}
          rowKey={(p) => p.code} empty={tt("list.empty")} />
      </Card>

      {editing && (
        <div className="grid items-start gap-4 lg:grid-cols-[1fr_340px]">
          <Card className="p-5">
            <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
              <h2 className="txt-strong">{editing.isNew ? tt("editor.newTitle") : tt("editor.editTitle", { name: editing.def.name[locale] || fallback(editing.def.name), code: editing.def.code })}</h2>
              <span className="txt-caption text-muted-foreground">{tt("editor.hint")}</span>
            </div>

            {errors.length > 0 && (
              <Notice className="border-[var(--destructive)] text-destructive-ink">
                {tt("editor.rejected", { n: errors.length, list: errors.map((e) => `${e.path} ${e.error}`).join(tc("listSep")) })}
              </Notice>
            )}

            <div className="mb-4 grid gap-3 sm:grid-cols-2">
              {editing.isNew && (
                <Field label={tt("field.code.label")} error={err("code")} help={tt("field.code.help")}>
                  <Input value={editing.def.code} aria-invalid={!!err("code")} onChange={(e) => set((d) => { d.code = e.target.value.toUpperCase(); })} />
                </Field>
              )}
              <Field label={tt("field.name.label")} error={err("name")} help={tt("field.name.help")}>
                <Input value={editing.def.name[locale] ?? ""} placeholder={fallback(editing.def.name)} aria-invalid={!!err("name")}
                  onChange={(e) => set((d) => { d.name[locale] = e.target.value; })} />
              </Field>
            </div>
            {err("steps") && <p className="mb-2 txt-caption text-destructive-ink">{err("steps")}</p>}

            <div className="space-y-3">
              {editing.def.steps.map((s, i) => {
                const p = (k: string) => `steps[${i}].${k}`;
                const kind = s.approver.kind;
                return (
                  <div key={i} className="rounded-card border border-[var(--border)] p-4">
                    <div className="mb-3 flex items-center justify-between gap-2">
                      <span className="txt-strong">{tt("step.title", { n: i + 1 })}{tc("sep")}{s.name[locale] || fallback(s.name) || tt("step.unnamed")}</span>
                      <span className="inline-flex gap-1">
                        <Button size="icon" variant="ghost" aria-label={tt("btn.moveUp")} disabled={i === 0} onClick={() => move(i, -1)}><ArrowUp /></Button>
                        <Button size="icon" variant="ghost" aria-label={tt("btn.moveDown")} disabled={i === editing.def.steps.length - 1} onClick={() => move(i, 1)}><ArrowDown /></Button>
                        <Button size="icon" variant="ghost" aria-label={tt("btn.removeStep")} disabled={editing.def.steps.length === 1}
                          onClick={() => set((d) => { d.steps.splice(i, 1); })}><Trash2 /></Button>
                      </span>
                    </div>
                    <div className="grid gap-3 sm:grid-cols-2">
                      <Field label={tt("field.stepName")} error={err(p("name"))}>
                        <Input value={s.name[locale] ?? ""} placeholder={fallback(s.name)} aria-invalid={!!err(p("name"))}
                          onChange={(e) => setStep(i, (x) => { x.name[locale] = e.target.value; })} />
                      </Field>
                      <Field label={tt("field.approver")} error={err(p("approver.value"))} help={tt(`approver.${kind}.hint`)}>
                        <div className="flex gap-2">
                          <Select className="w-36 shrink-0" value={s.approver.kind}
                            onChange={(e) => setStep(i, (x) => {
                              x.approver = withKind(x.approver, e.target.value as ApproverKind);
                              if (!allowsAll(x.approver.kind)) x.mode = "ANY";
                            })}>
                            {APPROVER_KINDS.map((a) => <option key={a} value={a}>{tt(`approver.${a}.label`)}</option>)}
                          </Select>
                          <Input value={approverText(s.approver)} aria-invalid={!!err(p("approver.value"))}
                            onChange={(e) => setStep(i, (x) => { x.approver = setApproverText(x.approver, e.target.value); })} />
                        </div>
                      </Field>
                      <Field label={tt("field.mode.label")} error={err(p("mode"))}
                        help={allowsAll(s.approver.kind) ? undefined : tt("field.mode.anyOnly")}>
                        <Select value={s.mode} aria-invalid={!!err(p("mode"))} onChange={(e) => setStep(i, (x) => { x.mode = e.target.value as "ANY" | "ALL"; })}>
                          <option value="ANY">{tt("mode.ANY")}</option>
                          <option value="ALL" disabled={!allowsAll(s.approver.kind)}>{tt("mode.ALL")}</option>
                        </Select>
                      </Field>
                      <Field label={tt("field.dueHours.label")} error={err(p("dueHours"))} help={tt("field.dueHours.help")}>
                        <Input type="number" min={1} max={720} value={s.dueHours ?? ""} aria-invalid={!!err(p("dueHours"))}
                          onChange={(e) => setStep(i, (x) => { x.dueHours = e.target.value === "" ? undefined : Number(e.target.value); })} />
                      </Field>
                      <Field label={tt("field.when.label")} error={err(p("when.field")) ?? err(p("when.value"))}
                        help={tt("field.when.help")} className="sm:col-span-2">
                        <div className="flex flex-wrap gap-2">
                          <Select className="w-44" value={s.when?.op ?? ""}
                            onChange={(e) => setStep(i, (x) => {
                              const op = e.target.value as "" | ConditionOp;
                              x.when = op ? { field: x.when?.field ?? "", op, value: x.when?.value } : undefined;
                            })}>
                            {COND.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
                          </Select>
                          {s.when && (
                            <Input className="w-44" placeholder={tt("field.when.fieldPh")} value={s.when.field} aria-invalid={!!err(p("when.field"))}
                              onChange={(e) => setStep(i, (x) => { x.when!.field = e.target.value; })} />
                          )}
                          {s.when && s.when.op !== "TRUE" && (
                            <Input className="w-44" placeholder={tt("field.when.valuePh")} value={s.when.value ?? ""} aria-invalid={!!err(p("when.value"))}
                              onChange={(e) => setStep(i, (x) => { x.when!.value = e.target.value; })} />
                          )}
                        </div>
                      </Field>
                    </div>
                  </div>
                );
              })}
              <Button variant="secondary" onClick={() => set((d) => { d.steps.push(blankStep()); })}><Plus className="size-4" /> {tt("btn.addStep")}</Button>
            </div>

            <TemplateFormFields fields={editing.def.fields ?? []} err={err}
              onChange={(fn) => set((d) => { d.fields ??= []; fn(d.fields); })} />

            <div className="mt-5 space-y-2">
              <Rule label={tt("rule.selfApproval")} checked={!editing.def.rules.selfApproval}
                onChange={(v) => set((d) => { d.rules.selfApproval = !v; })} />
              <Rule label={tt("rule.rejectReason")} locked />
              <Rule label={tt("rule.crossStep")} checked={editing.def.rules.crossStepExclusive}
                onChange={(v) => set((d) => { d.rules.crossStepExclusive = v; })} />
            </div>

            <div className="mt-5 flex flex-wrap items-end gap-3">
              <Field label={tt("field.note.label")} className="min-w-60 flex-1">
                <Input value={note} placeholder={tt("field.note.placeholder")} onChange={(e) => setNote(e.target.value)} />
              </Field>
              <Button variant="secondary" onClick={() => { setEditing(null); setErrors([]); }}>{tt("btn.close")}</Button>
              <Button loading={publish.isPending} onClick={() => publish.mutate()}>{tt("btn.publish")}</Button>
            </div>
          </Card>

          <div className="space-y-4">
            {!editing.isNew && (
              <Card className="p-5">
                <h2 className="mb-3 txt-strong">{tt("versions.title")}</h2>
                <FlowTimeline
                  empty={versions.isLoading ? tc("loading") : tt("versions.empty")}
                  items={(versions.data ?? []).map((v) => ({
                    key: v.version,
                    title: <span className="font-mono">{v.version}{v.current ? `${tc("sep")}${tt("versions.current")}` : ""}</span>,
                    meta: `${fmt.short(v.atMs)}${tc("sep")}${v.channel === "API" ? tc("channel.API") : tc("channel.CONSOLE")}${tc("sep")}${v.by}`,
                    detail: v.note,
                  }))}
                />
              </Card>
            )}
            <Card className="p-5">
              <h2 className="mb-2 txt-strong">{tt("bounds.title")}</h2>
              <ul className="list-disc space-y-1 ps-5 txt-body text-muted-foreground">
                <li>{tt("bounds.hash")}</li>
                <li>{tt("bounds.running")}</li>
                <li>{tt("bounds.approver")}</li>
                <li>{tt("bounds.cosign")}</li>
                <li>{tt("bounds.disable")}</li>
              </ul>
            </Card>
          </div>
        </div>
      )}
      {dialog}
    </div>
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

function Rule({ label, locked, checked, onChange }: { label: string; locked?: boolean; checked?: boolean; onChange?: (v: boolean) => void }) {
  const { t: tt } = useFlowT("processes");
  return (
    <div className="flex items-center gap-3">
      <Switch checked={locked ? true : !!checked} disabled={locked} onChange={onChange} aria-label={label} />
      <span className="txt-body">{label}</span>
      {locked && <span className="txt-caption text-muted-foreground">{tt("rule.locked")}</span>}
    </div>
  );
}

export default function FlowProcessesPage() {
  return <NeedPerm code="flow:process:manage" page="processes"><FlowProcesses /></NeedPerm>;
}
