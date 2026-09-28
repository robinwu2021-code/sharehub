"use client";

// 工单类型配置（运营配置 › 工单类型；OpsKit GET …/definition ↔ PUT …/ticket-types/{code}）。
// 编辑的是原始定义的副本：名称按当前界面语言改（其余语言保留），选项一行一个「代号 名称」，
// 编辑器不认识的键（scopeFrom / mergeTransition…）原样保留 —— 提交回去不丢配置。
// 引用选项集（options.set）的只读展示：选项集在别处维护，这里改不到。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { isValidationError } from "@/lib/flow/errors";
import { useFlowT } from "@/lib/flow/i18n";
import type { FieldError, I18nText, OptionSource, TicketFieldType, TicketTypeDef, TicketTypeDefinition } from "@/lib/flow/types";
import { notify } from "@/lib/notify";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { CheckboxField } from "@/components/ui/checkbox";
import { Notice } from "@/components/ui/notice";
import { DataTable, type Column } from "@/components/ui/data-table";

const FIELD_TYPES: TicketFieldType[] = ["TEXT", "LONG_TEXT", "NUMBER", "MONEY", "DATETIME", "SELECT", "MULTI_SELECT", "MEMBER", "GROUP", "ATTACHMENT", "REF"];
const STRATEGIES = ["MANUAL", "QUEUE", "ROUND_ROBIN", "LEAST_LOADED"] as const;
const hasOptions = (t: TicketFieldType) => t === "SELECT" || t === "MULTI_SELECT";

const blankDef = (): TicketTypeDefinition => ({
  code: "", name: {}, processCode: "", numberPrefix: "", fields: [],
  priorityOptions: { inline: [] }, resolutionOptions: { inline: [] }, assignment: { strategy: "MANUAL" }, slaPolicies: [],
});

/** 一行一个「代号 名称」。输入时只存草稿、失焦再解析 —— 边打边解析会把「只敲了代号」补成「代号 代号」 */
function OptionLines({ source, onChange, label, error }: {
  source?: OptionSource; onChange: (o: OptionSource) => void; label: string; error?: string;
}) {
  const { t, locale } = useFlowT("settings");
  const [draft, setDraft] = useState<string | null>(null);
  if (source?.set) return <p className="txt-caption text-muted-foreground">{t("types.optionSet", { set: source.set })}</p>;
  const text = (source?.inline ?? []).map((o) => `${o.code} ${o.name[locale] ?? o.name.en ?? o.name.zh ?? ""}`).join("\n");
  const commit = (v: string) => onChange({
    inline: v.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => {
      const [code, ...rest] = l.split(/\s+/);
      const prev = source?.inline?.find((o) => o.code === code);
      return { ...prev, code, name: { ...(prev?.name ?? {}), [locale]: rest.join(" ") || code } as I18nText };
    }),
  });
  return (
    <div>
      <Textarea rows={3} value={draft ?? text} aria-label={label} placeholder={t("types.optionsHelp")}
        onChange={setDraft} onBlur={() => { if (draft !== null) { commit(draft); setDraft(null); } }} />
      <p className={error ? "mt-1 txt-caption text-destructive-ink" : "mt-1 txt-caption text-muted-foreground"}>{error ?? t("types.optionsHelp")}</p>
    </div>
  );
}

export function TicketTypes() {
  const qc = useQueryClient();
  const { t, tc, locale } = useFlowT("settings");
  const types = useQuery({ queryKey: [...FLOW_KEY, "ticket-types"], queryFn: flow.ticketTypes });
  const [editing, setEditing] = useState<{ def: TicketTypeDefinition; isNew: boolean } | null>(null);
  const [errors, setErrors] = useState<FieldError[]>([]);
  const processes = useQuery({ queryKey: [...FLOW_KEY, "process-catalog"], queryFn: flow.processCatalog, enabled: !!editing });
  const slas = useQuery({ queryKey: [...FLOW_KEY, "sla-policies"], queryFn: flow.slaPolicies, enabled: !!editing });
  const groups = useQuery({ queryKey: [...FLOW_KEY, "queue-groups"], queryFn: flow.queueGroups, enabled: !!editing });
  const err = (path: string) => errors.find((e) => e.path === path)?.error;
  const pick = (n: I18nText) => n[locale] || n.en || n.zh || n.ar || "";
  const set = (fn: (d: TicketTypeDefinition) => void) => setEditing((cur) => {
    if (!cur) return cur;
    const def = structuredClone(cur.def);
    fn(def);
    return { ...cur, def };
  });

  const open = async (code: string) => {
    setErrors([]);
    try {
      setEditing({ def: await flow.ticketTypeDefinition(code), isNew: false });
    } catch (e) {
      notify.error((e as Error).message);
    }
  };
  const save = useMutation({
    mutationFn: () => flow.saveTicketType(editing!.def, editing!.isNew),
    onSuccess: (r) => {
      setErrors([]);
      notify.success(r.created ? t("types.saved", { v: r.version }) : t("types.unchanged"));
      if (editing?.isNew) setEditing({ def: editing.def, isNew: false });
      qc.invalidateQueries({ queryKey: FLOW_KEY });
    },
    onError: (e: Error) => {
      if (isValidationError(e)) setErrors(e.errors);
      notify.error(e.message);
    },
  });

  const cols: Column<TicketTypeDef>[] = [
    { header: t("types.col.code"), pk: true, cell: (x) => <span className="tabular-nums">{x.code}</span> },
    { header: t("types.col.name"), cell: (x) => x.name },
    { header: t("types.col.fields"), cell: (x) => x.fields.map((f) => f.name).join(tc("listSep")) || tc("none") },
    { header: t("types.col.priorities"), cell: (x) => x.priorityOptions.map((p) => p.name).join(" / ") },
    { header: t("types.col.actions"), className: "text-end",
      cell: (x) => <Button size="sm" variant="secondary" onClick={() => open(x.code)}>{t("btn.edit")}</Button> },
  ];

  const d = editing?.def;
  const processOptions = (processes.data ?? []).filter((p) => !p.fromTemplate);
  return (
    <Card className="mb-4 p-5" data-slot="ticket-types">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="txt-strong">{t("types.title")}</h2>
        <Button size="sm" onClick={() => { setErrors([]); setEditing({ def: blankDef(), isNew: true }); }}>
          <Plus className="size-4" /> {t("types.new")}
        </Button>
      </div>
      <DataTable columns={cols} rows={types.data} loading={types.isLoading} error={types.error} onRetry={() => types.refetch()}
        rowKey={(x) => x.code} empty={t("types.empty")} />

      {d && (
        <div data-slot="ticket-type-editor" className="mt-4 rounded-card bg-secondary/40 p-4">
          <h3 className="mb-3 txt-strong">{editing!.isNew ? t("types.newTitle") : t("types.editTitle", { name: pick(d.name) || d.code, code: d.code })}</h3>
          {errors.length > 0 && (
            <Notice className="border-[var(--destructive)] text-destructive-ink">
              {t("types.rejected", { n: errors.length, list: errors.map((e) => `${e.path} ${e.error}`).join(tc("listSep")) })}
            </Notice>
          )}
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {editing!.isNew && (
              <Labeled label={t("types.field.code")} error={err("code")} help={t("types.field.codeHelp")}>
                <Input value={d.code} aria-invalid={!!err("code")} onChange={(e) => set((x) => { x.code = e.target.value.trim().toLowerCase(); })} />
              </Labeled>
            )}
            <Labeled label={t("types.field.name")} error={err("name")}>
              <Input value={d.name[locale] ?? ""} placeholder={pick(d.name)} aria-invalid={!!err("name")}
                onChange={(e) => set((x) => { x.name = { ...x.name, [locale]: e.target.value }; })} />
            </Labeled>
            <Labeled label={t("types.field.prefix")} error={err("numberPrefix")} help={t("types.field.prefixHelp")}>
              <Input value={d.numberPrefix ?? ""} onChange={(e) => set((x) => { x.numberPrefix = e.target.value.trim().toUpperCase(); })} />
            </Labeled>
            <Labeled label={t("types.field.process")} error={err("processCode")}>
              <Select value={d.processCode} aria-invalid={!!err("processCode")} onChange={(e) => set((x) => { x.processCode = e.target.value; })}>
                <option value="">{t("types.pick")}</option>
                {d.processCode && !processOptions.some((p) => p.code === d.processCode) && <option value={d.processCode}>{d.processCode}</option>}
                {processOptions.map((p) => <option key={p.code} value={p.code}>{`${pick(p.name) || p.code} (${p.code})`}</option>)}
              </Select>
            </Labeled>
            <Labeled label={t("types.field.strategy")} error={err("assignment.strategy")} help={t(`types.strategy.${(d.assignment?.strategy ?? "MANUAL") as "MANUAL"}Help`)}>
              <Select value={d.assignment?.strategy ?? "MANUAL"}
                onChange={(e) => set((x) => { x.assignment = { ...x.assignment, strategy: e.target.value }; if (e.target.value === "MANUAL") delete x.assignment.group; })}>
                {STRATEGIES.map((s) => <option key={s} value={s}>{t(`types.strategy.${s}`)}</option>)}
              </Select>
            </Labeled>
            {(d.assignment?.strategy ?? "MANUAL") !== "MANUAL" && (
              <Labeled label={t("types.field.group")} error={err("assignment.group")}>
                <Select value={d.assignment?.group ?? ""} aria-invalid={!!err("assignment.group")}
                  onChange={(e) => set((x) => { x.assignment = { ...x.assignment!, group: e.target.value || undefined }; })}>
                  <option value="">{t("types.pick")}</option>
                  {(groups.data ?? []).map((g) => <option key={g.code} value={g.code}>{g.name}</option>)}
                </Select>
              </Labeled>
            )}
          </div>

          <div className="mt-3">
            <p className="mb-1 txt-caption text-muted-foreground">{t("types.field.sla")}</p>
            <div className="flex flex-wrap gap-4">
              {(slas.data ?? []).map((p) => (
                <CheckboxField key={p.code} label={`${pick(p.name) || p.code}`} checked={(d.slaPolicies ?? []).includes(p.code)}
                  onChange={(v) => set((x) => {
                    const cur = new Set(x.slaPolicies ?? []);
                    if (v === true) cur.add(p.code); else cur.delete(p.code);
                    x.slaPolicies = [...cur];
                  })} />
              ))}
            </div>
          </div>

          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            <Labeled label={t("types.field.priorities")}>
              <OptionLines source={d.priorityOptions} label={t("types.field.priorities")} error={err("priorityOptions")}
                onChange={(o) => set((x) => { x.priorityOptions = o; })} />
            </Labeled>
            <Labeled label={t("types.field.resolutions")}>
              <OptionLines source={d.resolutionOptions} label={t("types.field.resolutions")} error={err("resolutionOptions")}
                onChange={(o) => set((x) => { x.resolutionOptions = o; })} />
            </Labeled>
          </div>

          <h4 className="mb-2 mt-4 txt-strong">{t("types.fields.title")}</h4>
          <div className="space-y-2">
            {d.fields.map((f, i) => {
              const at = (k: string) => err(`fields[${i}].${k}`);
              return (
                <div key={i} className="rounded-card bg-card p-3">
                  <div className="grid gap-2 sm:grid-cols-[9rem_1fr_10rem_auto] sm:items-start">
                    <div>
                      <Input value={f.code} placeholder={t("types.fields.code")} aria-label={t("types.fields.code")} aria-invalid={!!at("code")}
                        onChange={(e) => set((x) => { x.fields[i].code = e.target.value.trim(); })} />
                      {at("code") && <p className="mt-1 txt-caption text-destructive-ink">{at("code")}</p>}
                    </div>
                    <div>
                      <Input value={f.name[locale] ?? ""} placeholder={pick(f.name) || t("types.fields.name")} aria-label={t("types.fields.name")}
                        aria-invalid={!!at("name")} onChange={(e) => set((x) => { x.fields[i].name = { ...x.fields[i].name, [locale]: e.target.value }; })} />
                      {at("name") && <p className="mt-1 txt-caption text-destructive-ink">{at("name")}</p>}
                    </div>
                    <Select value={f.type} aria-label={t("types.fields.type")}
                      onChange={(e) => set((x) => {
                        const ty = e.target.value as TicketFieldType;
                        x.fields[i].type = ty;
                        if (!hasOptions(ty)) delete x.fields[i].options; else x.fields[i].options ??= { inline: [] };
                      })}>
                      {FIELD_TYPES.map((ty) => <option key={ty} value={ty}>{t(`types.fieldType.${ty}`)}</option>)}
                    </Select>
                    <Button size="icon" variant="ghost" aria-label={t("types.fields.remove")} onClick={() => set((x) => { x.fields.splice(i, 1); })}><Trash2 /></Button>
                  </div>
                  <div className="mt-2 flex flex-wrap gap-4">
                    <CheckboxField label={t("types.fields.required")} checked={f.required} onChange={(v) => set((x) => { x.fields[i].required = v === true; if (v === true) x.fields[i].onCreate = true; })} />
                    <CheckboxField label={t("types.fields.onCreate")} checked={f.onCreate} onChange={(v) => set((x) => { x.fields[i].onCreate = v === true; })} />
                    <CheckboxField label={t("types.fields.filterable")} checked={!!f.filterable} onChange={(v) => set((x) => { x.fields[i].filterable = v === true; })} />
                  </div>
                  {at("onCreate") && <p className="mt-1 txt-caption text-destructive-ink">{at("onCreate")}</p>}
                  {hasOptions(f.type) && (
                    <div className="mt-2">
                      <OptionLines source={f.options} label={t("types.fields.options")} error={at("options")}
                        onChange={(o) => set((x) => { x.fields[i].options = o; })} />
                    </div>
                  )}
                </div>
              );
            })}
            <Button variant="secondary" onClick={() => set((x) => { x.fields.push({ code: "", name: {}, type: "TEXT", required: false, onCreate: true }); })}>
              <Plus className="size-4" /> {t("types.fields.add")}
            </Button>
          </div>

          <div className="mt-4 flex flex-wrap justify-end gap-2">
            <Button variant="secondary" onClick={() => { setEditing(null); setErrors([]); }}>{t("types.close")}</Button>
            <Button loading={save.isPending} onClick={() => save.mutate()}>{t("types.save")}</Button>
          </div>
        </div>
      )}
    </Card>
  );
}

function Labeled({ label, error, help, children }: { label: string; error?: string; help?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block txt-caption text-muted-foreground">{label}</span>
      {children}
      {error ? <span className="mt-1 block txt-caption text-destructive-ink">{error}</span>
        : help ? <span className="mt-1 block txt-caption text-muted-foreground">{help}</span> : null}
    </label>
  );
}
